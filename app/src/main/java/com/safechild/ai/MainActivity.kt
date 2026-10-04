package com.safechild.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.safechild.ai.utils.LocationHelper
import com.safechild.ai.data.SafetyEventRepository
import com.safechild.ai.services.SosNotificationManager
import com.safechild.ai.services.AiReviewNotificationManager
import com.safechild.ai.services.ParentAiRoutineMonitor
import com.safechild.ai.ui.screens.*
import com.safechild.ai.ui.theme.SafeChildAITheme

private fun resolveStartDestination(role: String?, parentUid: String?, isAnonymous: Boolean): String =
    when (role?.trim()?.lowercase()) {
        "parent" -> "parent_dashboard"
        "child" -> if (parentUid.isNullOrBlank()) "child_access" else "child_dashboard"
        else -> if (isAnonymous) "child_access" else "welcome"
    }
class MainActivity : ComponentActivity() {
    private var pendingAiReviewChildUid by mutableStateOf<String?>(null)
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var functions: FirebaseFunctions
    private lateinit var locationHelper: LocationHelper

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAiReviewChildUid = intent.getStringExtra(AiReviewNotificationManager.EXTRA_CHILD_UID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        functions = FirebaseFunctions.getInstance()
        locationHelper = LocationHelper(this, firestore)
        pendingAiReviewChildUid = intent.getStringExtra(AiReviewNotificationManager.EXTRA_CHILD_UID)
        SosNotificationManager.createChannel(this)
        AiReviewNotificationManager.createChannel(this)
        
        enableEdgeToEdge()
        setContent {
            var startDestination by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                val onboardingComplete = getSharedPreferences("safechild_preferences", MODE_PRIVATE)
                    .getBoolean("onboarding_complete", false)
                val currentUser = auth.currentUser
                if (!onboardingComplete) {
                    startDestination = "welcome"
                } else if (currentUser == null) {
                    startDestination = "welcome"
                } else {
                    firestore.collection("users").document(currentUser.uid).get()
                        .addOnSuccessListener { profile ->
                            startDestination = resolveStartDestination(
                                role = profile.getString("role"),
                                parentUid = profile.getString("parentUid"),
                                isAnonymous = currentUser.isAnonymous
                            )
                            Log.d("StartupRoute", "uid=${currentUser.uid}, role=${profile.getString("role")}, destination=$startDestination")
                        }
                        .addOnFailureListener { error ->
                            Log.e("StartupRoute", "Could not load profile for ${currentUser.uid}; avoiding parent dashboard", error)
                            startDestination = if (currentUser.isAnonymous) "child_access" else "welcome"
                        }
                }
            }

            SafeChildAITheme {
                val destination = startDestination
                if (destination == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    SafeChildApp(auth, firestore, functions, locationHelper, destination, pendingAiReviewChildUid) { pendingAiReviewChildUid = null }
                }
            }
        }
    }
}

@Composable
fun SafeChildApp(
    auth: FirebaseAuth, 
    firestore: FirebaseFirestore, 
    functions: FirebaseFunctions,
    locationHelper: LocationHelper,
    startDestination: String,
    pendingAiReviewChildUid: String? = null,
    onAiReviewOpened: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val parentUid = auth.currentUser?.uid.orEmpty()
    val isSignedInParentRoute = currentRoute?.startsWith("parent_") == true &&
        currentRoute !in setOf("parent_login", "parent_registration", "parent_privacy_consent")
    LaunchedEffect(pendingAiReviewChildUid, isSignedInParentRoute) {
        val childUid = pendingAiReviewChildUid
        if (childUid.isNullOrBlank() || !isSignedInParentRoute) return@LaunchedEffect
        navController.navigate("parent_history/$childUid") { launchSingleTop = true }
        onAiReviewOpened()
    }

    DisposableEffect(parentUid, isSignedInParentRoute) {
        if (parentUid.isBlank() || !isSignedInParentRoute) return@DisposableEffect onDispose { }
        val registration = SafetyEventRepository.listenForParentSosAlerts(
            context = appContext,
            firestore = firestore,
            parentUid = parentUid,
            onNewSos = { event -> SosNotificationManager.showSos(appContext, parentUid, event) },
            onError = { error -> Log.e("SosNotifications", "Could not monitor parent SOS events", error) }
        )
        val aiMonitor = ParentAiRoutineMonitor(appContext, firestore, parentUid)
        aiMonitor.start()
        onDispose { registration.remove(); aiMonitor.stop() }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> Log.i("SosNotifications", "POST_NOTIFICATIONS granted=$granted") }
    )
    LaunchedEffect(parentUid, isSignedInParentRoute) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || parentUid.isBlank() || !isSignedInParentRoute) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        val preferences = context.getSharedPreferences("safechild_preferences", android.content.Context.MODE_PRIVATE)
        if (!preferences.getBoolean("sos_notification_permission_requested", false)) {
            preferences.edit().putBoolean("sos_notification_permission_requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun markOnboardingComplete() {
        context.getSharedPreferences("safechild_preferences", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_complete", true).apply()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("welcome") {
            WelcomeScreen(
                onGetStartedClick = {
                    navController.navigate("role_selection")
                }
            )
        }
        composable("role_selection") {
            RoleSelectionScreen(
                onParentRoleSelected = {
                    navController.navigate("parent_login")
                },
                onChildRoleSelected = {
                    navController.navigate("child_access")
                }
            )
        }
        composable("parent_login") {
            ParentLoginScreen(
                auth = auth,
                onBackClick = { navController.popBackStack() },
                onLoginSuccess = { 
                    markOnboardingComplete()
                    navController.navigate("parent_dashboard") {
                        popUpTo("welcome") { inclusive = true }
                    }
                },
                onCreateAccountClick = { navController.navigate("parent_registration") }
            )
        }
        composable("parent_registration") {
            ParentRegistrationScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onRegisterSuccess = { navController.navigate("parent_privacy_consent") }
            )
        }
        composable("parent_privacy_consent") {
            PrivacyConsentScreen(
                onContinueClick = {
                    markOnboardingComplete()
                    navController.navigate("parent_dashboard") {
                        popUpTo("welcome") { inclusive = true }
                    }
                }
            )
        }
        composable("parent_dashboard") {
            ParentDashboardScreen(
                auth = auth,
                firestore = firestore,
                onLogoutClick = {
                    auth.signOut()
                    navController.navigate("welcome") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onChildSelected = { childUid ->
                    navController.navigate("parent_map/$childUid")
                },
                onNavigateToMap = { 
                    navController.navigate("parent_map/all") 
                },
                onNavigateToAlerts = { navController.navigate("parent_alerts/all") },
                onNavigateToHistory = { navController.navigate("parent_history/all") },
                onNavigateToSettings = { navController.navigate("parent_settings") },
                onNavigateToTrustedLocations = { navController.navigate("parent_trusted_locations") },
                onNavigateToChildConnection = { navController.navigate("parent_child_connection") },
                onNavigateToSos = { navController.navigate("parent_sos") }
            )
        }
        composable(
            route = "parent_map/{childUid}",
            arguments = listOf(navArgument("childUid") { type = NavType.StringType })
        ) { backStackEntry ->
            val childUid = backStackEntry.arguments?.getString("childUid")
            ParentMapLocationScreen(
                auth = auth,
                firestore = firestore,
                childUidParam = childUid,
                onBackClick = { navController.popBackStack() },
                onManageTrustedLocationsClick = { navController.navigate("parent_trusted_locations") },
                onViewHistoryClick = { 
                    val uid = if (childUid == "all") "all" else childUid ?: "all"
                    navController.navigate("parent_history/$uid") 
                }
            )
        }
        
        composable(
            route = "parent_alerts/{childUid}",
            arguments = listOf(navArgument("childUid") { type = NavType.StringType })
        ) {
            ParentSosScreen(auth, firestore, onBackClick = { navController.popBackStack() })
        }

        composable(
            route = "parent_history/{childUid}",
            arguments = listOf(navArgument("childUid") { type = NavType.StringType })
        ) { backStackEntry ->
            val childUid = backStackEntry.arguments?.getString("childUid")
            ParentRoutineScreen(
                auth = auth,
                firestore = firestore,
                childUid = childUid,
                onBackClick = { navController.popBackStack() },
                onViewAlertsClick = { navController.navigate("parent_alerts/$childUid") },
                onHomeClick = { navController.navigate("parent_dashboard") },
                onMapClick = { navController.navigate("parent_map/$childUid") },
                onAlertsClick = { navController.navigate("parent_alerts/$childUid") },
                onSettingsClick = { navController.navigate("parent_settings") }
            )
        }
        
        composable("parent_settings") { 
            ParentSettingsScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onProfileClick = { navController.navigate("parent_profile") },
                onChangePasswordClick = {
                    val email = auth.currentUser?.email
                    if (email.isNullOrBlank()) Toast.makeText(context, "No email is linked to this account.", Toast.LENGTH_LONG).show()
                    else auth.sendPasswordResetEmail(email)
                        .addOnSuccessListener { Toast.makeText(context, "Password reset email sent to $email", Toast.LENGTH_LONG).show() }
                        .addOnFailureListener { Toast.makeText(context, it.localizedMessage ?: "Could not send password reset email.", Toast.LENGTH_LONG).show() }
                },
                onSafetySettingsClick = { navController.navigate("parent_safety_settings") },
                onPrivacyDataClick = { navController.navigate("privacy_data") },
                onConnectedChildClick = { navController.navigate("parent_child_connection") },
                onAboutClick = { navController.navigate("about") },
                onLogoutClick = {
                    auth.signOut()
                    navController.navigate("welcome") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("parent_profile") {
            ParentProfileScreen(auth, firestore, onBackClick = { navController.popBackStack() })
        }

        composable("parent_safety_settings") {
            ParentSafetySettingsScreen(onBackClick = { navController.popBackStack() })
        }

        composable("parent_child_connection") {
            ChildConnectionScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onBackToDashboardClick = {
                    if (!navController.popBackStack("parent_dashboard", false)) {
                        navController.navigate("parent_dashboard") {
                            popUpTo("welcome") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onConnectedChildClick = { childUid -> navController.navigate("parent_map/$childUid") }
            )
        }

        composable("privacy_data") {
            PrivacyAndDataScreen(onBackClick = { navController.popBackStack() })
        }

        composable("about") {
            AboutScreen(onBackClick = { navController.popBackStack() })
        }
        
        composable("parent_trusted_locations") { 
            TrustedLocationsScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onAddLocationClick = { navController.navigate("parent_add_trusted_location") },
                onEditLocationClick = { location ->
                    navController.navigate("parent_edit_trusted_location/${location.id}")
                }
            )
        }

        composable("parent_add_trusted_location") {
            AddEditTrustedLocationScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onSaveClick = { navController.popBackStack() },
                onDeleteClick = { navController.popBackStack() }
            )
        }

        composable(
            route = "parent_edit_trusted_location/{locationId}",
            arguments = listOf(navArgument("locationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val locationId = backStackEntry.arguments?.getString("locationId")
            AddEditTrustedLocationScreen(
                auth = auth,
                firestore = firestore,
                locationIdToEdit = locationId,
                onBackClick = { navController.popBackStack() },
                onSaveClick = { navController.popBackStack() },
                onDeleteClick = { navController.popBackStack() }
            )
        }

        composable("parent_sos") { ParentSosScreen(auth, firestore, onBackClick = { navController.popBackStack() }) }

        composable("child_access") {
            ChildAccessScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onContinueClick = {
                    navController.navigate("child_privacy_consent") {
                        popUpTo("child_access") { inclusive = true }
                    }
                }
            )
        }

        composable("child_privacy_consent") {
            PrivacyConsentScreen(
                onContinueClick = {
                    markOnboardingComplete()
                    navController.navigate("child_dashboard") {
                        popUpTo("welcome") { inclusive = false }
                    }
                }
            )
        }

        composable("child_dashboard") {
            ChildDashboardScreen(
                auth = auth,
                firestore = firestore,
                onLogoutClick = {
                    auth.signOut()
                    navController.navigate("welcome") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToLocation = { navController.navigate("child_location") },
                onNavigateToCheckIn = { navController.navigate("child_checkin") },
                onNavigateToHelp = { navController.navigate("child_help") },
                onNavigateToSettings = { navController.navigate("child_settings") },
                onNavigateToPrivacy = { navController.navigate("child_privacy") }
            )
        }
        composable("child_location") { 
            ChildLocationScreen(
                auth = auth,
                onBackClick = { navController.popBackStack() }
            ) 
        }
        composable("child_checkin") { ChildCheckInScreen(auth, firestore, onBackClick = { navController.popBackStack() }) }
        composable("child_sos") { ChildSosScreen(auth, firestore, onBackClick = { navController.popBackStack() }) }
        composable("child_help") { 
            ChildHelpScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onCheckInClick = { navController.navigate("child_checkin") },
                onSosClick = { navController.navigate("child_sos") }
            )
        }
        composable("child_settings") { 
            ChildSettingsScreen(
                auth = auth,
                firestore = firestore,
                onBackClick = { navController.popBackStack() },
                onPrivacyClick = { navController.navigate("child_privacy") },
                onPrivacyDataClick = { navController.navigate("privacy_data") },
                onAboutClick = { navController.navigate("about") },
                onLogoutClick = {
                    auth.signOut()
                    navController.navigate("welcome") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable("child_privacy") {
            ChildPrivacyScreen(
                onBackClick = { navController.popBackStack() },
                onLearnMoreClick = { navController.navigate("privacy_data") }
            )
        }
    }
}





