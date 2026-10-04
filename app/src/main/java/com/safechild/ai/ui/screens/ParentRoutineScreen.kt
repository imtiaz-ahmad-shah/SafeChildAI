package com.safechild.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import com.safechild.ai.ui.theme.SafeChildAITheme
import com.safechild.ai.data.RoutineDemoModel
import com.safechild.ai.services.AiReviewNotificationManager
import com.safechild.ai.ui.components.OpenStreetMapView
import com.safechild.ai.data.models.TrustedLocation
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private data class RoutineChild(val uid: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentRoutineScreen(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    childUid: String?,
    onBackClick: () -> Unit,
    onViewAlertsClick: () -> Unit,
    onHomeClick: () -> Unit,
    onMapClick: () -> Unit,
    onAlertsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("AI Review", "Activity")
    val parentUid = auth.currentUser?.uid
    val context = LocalContext.current
    var children by remember(parentUid) { mutableStateOf<List<RoutineChild>>(emptyList()) }
    var isLoadingChildren by remember(parentUid) { mutableStateOf(true) }
    var childLoadError by remember(parentUid) { mutableStateOf<String?>(null) }
    var selectedChildUid by remember(childUid) { mutableStateOf(childUid?.takeUnless { it == "all" }) }

    LaunchedEffect(parentUid) {
        if (parentUid.isNullOrBlank()) {
            isLoadingChildren = false
            childLoadError = "Sign in to view child activity."
        } else {
            firestore.collection("relationships")
                .whereEqualTo("parentUid", parentUid)
                .whereEqualTo("status", "active")
                .get()
                .addOnSuccessListener { relationships ->
                    val relationshipDocs = relationships.documents
                    val childUids = relationshipDocs.mapNotNull { it.getString("childUid") }.distinct()
                    if (childUids.isEmpty()) {
                        children = emptyList()
                        isLoadingChildren = false
                    } else {
                        val loadedChildren = mutableListOf<RoutineChild>()
                        var remaining = childUids.size
                        childUids.forEach { uid ->
                            val relationshipName = relationshipDocs.firstOrNull { it.getString("childUid") == uid }
                                ?.getString("childName")
                            firestore.collection("users").document(uid).get()
                                .addOnSuccessListener { profile ->
                                    val name = profile.getString("name")?.takeIf { it.isNotBlank() }
                                        ?: relationshipName?.takeIf { it.isNotBlank() }
                                        ?: "Child"
                                    loadedChildren.add(RoutineChild(uid, name))
                                    remaining--
                                    if (remaining == 0) {
                                        children = loadedChildren.sortedBy { it.name.lowercase(Locale.getDefault()) }
                                        isLoadingChildren = false
                                    }
                                }
                                .addOnFailureListener {
                                    loadedChildren.add(RoutineChild(uid, relationshipName ?: "Child"))
                                    remaining--
                                    if (remaining == 0) {
                                        children = loadedChildren.sortedBy { it.name.lowercase(Locale.getDefault()) }
                                        isLoadingChildren = false
                                    }
                                }
                        }
                    }
                }
                .addOnFailureListener { error ->
                    childLoadError = error.localizedMessage ?: "Could not load connected children."
                    isLoadingChildren = false
                }
        }
    }

    LaunchedEffect(children, childUid) {
        val preferredUid = childUid?.takeUnless { it == "all" }
        selectedChildUid = preferredUid?.takeIf { requested -> children.any { it.uid == requested } }
            ?: children.firstOrNull()?.uid
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI & Activity", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            SafeChildBottomNavigation(
                currentRoute = "history",
                onHomeClick = onHomeClick,
                onMapClick = onMapClick,
                onAlertsClick = onAlertsClick,
                onHistoryClick = { },
                onSettingsClick = onSettingsClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when {
                isLoadingChildren -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                childLoadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(childLoadError!!, color = MaterialTheme.colorScheme.error)
                }
                children.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Connect a child to see recorded activity.", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    if (children.size > 1) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(children, key = { it.uid }) { child ->
                                FilterChip(
                                    selected = selectedChildUid == child.uid,
                                    onClick = { selectedChildUid = child.uid },
                                    label = { Text(child.name) }
                                )
                            }
                        }
                    }
                    val activeChildUid = selectedChildUid
                    if (activeChildUid == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val activeChildName = children.firstOrNull { it.uid == activeChildUid }?.name ?: "Child"
                        Box(Modifier.fillMaxSize()) {
                            ObservedActivityContent(firestore, context, parentUid, activeChildUid, activeChildName, isVisible = selectedTab == 0)
                            if (selectedTab == 1) ActivityHistoryContent(firestore, activeChildUid)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObservedActivityContent(
    firestore: FirebaseFirestore,
    context: Context,
    parentUid: String?,
    childUid: String?,
    childName: String,
    isVisible: Boolean
) {
    var liveAssessment by remember(childUid) { mutableStateOf<RoutineDemoModel.Assessment?>(null) }
    var readiness by remember(childUid) { mutableStateOf<RoutineDemoModel.UnsupervisedReadiness?>(null) }
    var isLoadingHistory by remember(childUid) { mutableStateOf(false) }
    var status by remember(childUid) { mutableStateOf<String?>(null) }

    DisposableEffect(firestore, parentUid, childUid, isVisible) {
        val uid = childUid
        if (!isVisible || uid.isNullOrBlank() || parentUid.isNullOrBlank()) onDispose { }
        else {
            isLoadingHistory = true
            val historyRegistration = firestore.collection("users").document(uid)
                .collection("locationHistory").orderBy("timestamp", Query.Direction.DESCENDING).limit(5000)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        status = error.localizedMessage ?: "Could not load location history."
                        isLoadingHistory = false
                        return@addSnapshotListener
                    }
                    val points = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        val lat = (doc.get("latitude") as? Number)?.toDouble()
                        val lon = (doc.get("longitude") as? Number)?.toDouble()
                        val time = doc.getTimestamp("timestamp")?.toDate()?.time
                        val accuracy = (doc.get("accuracy") as? Number)?.toDouble() ?: Double.NaN
                        if (lat == null || lon == null || time == null) null else RoutineDemoModel.HistoryPoint(lat, lon, time, accuracy)
                    }
                    readiness = RoutineDemoModel.unsupervisedReadiness(points)
                    isLoadingHistory = false
                }
            val locationRegistration = firestore.collection("users").document(uid).collection("location").document("current")
                .addSnapshotListener { location, error ->
                    if (error != null) {
                        status = error.localizedMessage ?: "Could not watch live location."
                        return@addSnapshotListener
                    }
                    if (location == null || !location.exists()) {
                        liveAssessment = null
                        status = "Waiting for the child’s first location update."
                        return@addSnapshotListener
                    }
                    val lat = (location.get("latitude") as? Number)?.toDouble()
                    val lon = (location.get("longitude") as? Number)?.toDouble()
                    val time = location.getTimestamp("timestamp")?.toDate()?.time
                    val accuracy = (location.get("accuracy") as? Number)?.toDouble() ?: Double.NaN
                    if (lat == null || lon == null || time == null) {
                        liveAssessment = null
                        status = "Waiting for a complete location update."
                        return@addSnapshotListener
                    }
                    val age = (System.currentTimeMillis() - time).coerceAtLeast(0L)
                    if (age > 20 * 60 * 1000L) {
                        liveAssessment = RoutineDemoModel.Assessment("Location update is old", false,
                            "The last location was updated " + (age / 60_000) + " minutes ago. The AI will score a fresh update.")
                        return@addSnapshotListener
                    }
                    val assessment = RoutineDemoModel.assessUnsupervised(context, uid, RoutineDemoModel.HistoryPoint(lat, lon, time, accuracy))
                    liveAssessment = assessment
                    if (assessment == null) {
                        status = "Isolation Forest is learning from real location history. Scoring starts automatically when enough data is available."
                    } else {
                        status = null
                        val isNew = RoutineDemoModel.recordAssessment(context, uid, false, assessment, time, lat, lon)
                        val record = RoutineDemoModel.loadReviewHistory(context, uid, false).firstOrNull { it.title == assessment.title && it.lastSeenMillis == time }
                        if (record != null) {
                            val data = mutableMapOf<String, Any>(
                                "parentUid" to parentUid, "childUid" to uid, "title" to record.title,
                                "explanation" to record.explanation, "firstSeenMillis" to record.firstSeenMillis,
                                "lastSeenMillis" to record.lastSeenMillis, "latitude" to record.latitude,
                                "longitude" to record.longitude, "synthetic" to false,
                                "needsReview" to record.needsReview, "updatedAt" to FieldValue.serverTimestamp())
                            record.feedback?.let { data["feedback"] = it }
                            firestore.collection("users").document(uid).collection("aiReviewHistory")
                                .document(RoutineDemoModel.reviewDocumentId(uid, record)).set(data, SetOptions.merge())
                                .addOnFailureListener { writeError -> Log.w("ParentRoutine", "Could not sync AI review history.", writeError) }
                        }
                        if (isNew && assessment.needsReview) AiReviewNotificationManager.show(context, uid, childName, assessment)
                    }
                }
            onDispose { historyRegistration.remove(); locationRegistration.remove() }
        }
    }

    if (!isVisible) return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI learning · " + childName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Isolation Forest learns from this child’s real location history and starts scoring automatically when history is sufficient.", style = MaterialTheme.typography.bodyMedium)
                when {
                    isLoadingHistory -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Reading location history…", style = MaterialTheme.typography.bodySmall)
                    }
                    readiness == null -> Text("Waiting for real location history.", style = MaterialTheme.typography.bodySmall)
                    readiness!!.ready -> Text("AI scoring is active · " + readiness!!.usableSamples + " accurate readings across " + readiness!!.distinctDays + " days.", style = MaterialTheme.typography.bodySmall)
                    else -> Text("Learning from real data · " + readiness!!.usableSamples + "/36 accurate readings · " + readiness!!.distinctDays + "/3 days. Keep location sharing on.", style = MaterialTheme.typography.bodySmall)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                liveAssessment?.let { assessment ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (assessment.needsReview) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(assessment.title, fontWeight = FontWeight.SemiBold); Text(assessment.explanation, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("AI review is advisory. Confirm unusual activity on the map and with the child.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ActivityHistoryContent(firestore: FirebaseFirestore, childUid: String?) {
    val context = LocalContext.current
    val parentUid = FirebaseAuth.getInstance().currentUser?.uid
    var localReviewHistory by remember(childUid) {
        mutableStateOf(childUid?.let { uid -> RoutineDemoModel.loadReviewHistory(context, uid, false).sortedByDescending { it.lastSeenMillis } }.orEmpty())
    }
    var cloudReviewHistory by remember(childUid) { mutableStateOf<List<RoutineDemoModel.ReviewRecord>>(emptyList()) }
    val reviewHistory = remember(localReviewHistory, cloudReviewHistory, childUid) {
        val merged = localReviewHistory.associateBy { record -> childUid?.let { RoutineDemoModel.reviewDocumentId(it, record) } ?: record.firstSeenMillis.toString() }.toMutableMap()
        cloudReviewHistory.forEach { cloudRecord ->
            val key = childUid?.let { RoutineDemoModel.reviewDocumentId(it, cloudRecord) } ?: cloudRecord.firstSeenMillis.toString()
            val localRecord = merged[key]
            merged[key] = cloudRecord.copy(feedback = cloudRecord.feedback ?: localRecord?.feedback)
        }
        merged.values.sortedByDescending { it.lastSeenMillis }
    }
    DisposableEffect(childUid, parentUid) {
        val uid = childUid
        if (uid.isNullOrBlank() || uid == "all" || parentUid.isNullOrBlank()) {
            onDispose { }
        } else {
            fun refreshLocal() {
                localReviewHistory = (RoutineDemoModel.loadReviewHistory(context, uid, false))
                    .sortedByDescending { it.lastSeenMillis }
            }
                        val stopRealObserver = RoutineDemoModel.observeReviewHistory(context, uid, false) { refreshLocal() }
            val cloudRegistration = firestore.collection("users").document(uid).collection("aiReviewHistory")
                .orderBy("lastSeenMillis", Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("ActivityHistory", "Could not load synced AI review history.", error)
                        return@addSnapshotListener
                    }
                    cloudReviewHistory = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        val title = doc.getString("title") ?: return@mapNotNull null
                        val firstSeen = (doc.get("firstSeenMillis") as? Number)?.toLong() ?: return@mapNotNull null
                        val lastSeen = (doc.get("lastSeenMillis") as? Number)?.toLong() ?: return@mapNotNull null
                        val latitude = (doc.get("latitude") as? Number)?.toDouble() ?: return@mapNotNull null
                        val longitude = (doc.get("longitude") as? Number)?.toDouble() ?: return@mapNotNull null
                        RoutineDemoModel.ReviewRecord(
                            title = title,
                            explanation = doc.getString("explanation") ?: "",
                            firstSeenMillis = firstSeen,
                            lastSeenMillis = lastSeen,
                            latitude = latitude,
                            longitude = longitude,
                            feedback = doc.getString("feedback"),
                            synthetic = doc.getBoolean("synthetic") ?: false,
                            needsReview = doc.getBoolean("needsReview") ?: true
                        )
                    }.filterNot { it.synthetic }
                }
            onDispose {
                stopRealObserver()
                cloudRegistration.remove()
            }
        }
    }
    LaunchedEffect(childUid, parentUid, localReviewHistory, cloudReviewHistory) {
        val uid = childUid
        val parent = parentUid
        if (uid.isNullOrBlank() || uid == "all" || parent.isNullOrBlank()) return@LaunchedEffect
        val cloudIds = cloudReviewHistory.mapTo(mutableSetOf()) { RoutineDemoModel.reviewDocumentId(uid, it) }
        localReviewHistory.filter { RoutineDemoModel.reviewDocumentId(uid, it) !in cloudIds }.forEach { record ->
            val recordData = mutableMapOf<String, Any>(
                "parentUid" to parent,
                "childUid" to uid,
                "title" to record.title,
                "explanation" to record.explanation,
                "firstSeenMillis" to record.firstSeenMillis,
                "lastSeenMillis" to record.lastSeenMillis,
                "latitude" to record.latitude,
                "longitude" to record.longitude,
                "synthetic" to record.synthetic,
                "needsReview" to record.needsReview,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            record.feedback?.let { recordData["feedback"] = it }
            firestore.collection("users").document(uid).collection("aiReviewHistory")
                .document(RoutineDemoModel.reviewDocumentId(uid, record))
                .set(recordData, SetOptions.merge())
                .addOnFailureListener { error -> Log.w("ActivityHistory", "Could not sync saved AI review history.", error) }
        }
    }
    var feedbackSyncMessage by remember(childUid) { mutableStateOf<String?>(null) }
    fun saveReviewFeedback(record: RoutineDemoModel.ReviewRecord, feedback: String) {
        val uid = childUid
        val parent = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank() || uid == "all" || parent.isNullOrBlank()) {
            feedbackSyncMessage = "Sign in as the linked parent to sync feedback."
            return
        }
        RoutineDemoModel.updateReviewFeedback(context, uid, record, feedback)
        feedbackSyncMessage = "Syncing feedback…"
        firestore.collection("users").document(uid).collection("aiReviewHistory")
            .document(RoutineDemoModel.reviewDocumentId(uid, record))
            .set(mapOf("feedback" to feedback, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
            .addOnSuccessListener { feedbackSyncMessage = "Feedback synced to the linked child record." }
            .addOnFailureListener {
                feedbackSyncMessage = "Saved on this phone, but Firestore sync failed. Check that the updated rules are published."
                Log.w("ActivityHistory", "Could not sync parent feedback.", it)
            }
    }
    var expandedReviewKey by remember(childUid) { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf("All") }
    val dates = listOf("All", "Today", "Yesterday", "Older")
    
    var events by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(childUid, selectedDate) {
        if (childUid == null || childUid == "all") {
            isLoading = false
            return@LaunchedEffect
        }
        
        isLoading = true
        firestore.collection("users").document(childUid)
            .collection("locationEvents")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { result ->
                events = result.documents.mapNotNull { it.data }
                isLoading = false
            }
            .addOnFailureListener { isLoading = false }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Date Selector
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(dates) { date ->
                FilterChip(
                    selected = selectedDate == date,
                    onClick = { selectedDate = date },
                    label = { Text(date) },
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }

        val now = Calendar.getInstance()
        val filteredReviewHistory = reviewHistory.filter { record ->
            val reviewCal = Calendar.getInstance().apply { timeInMillis = record.lastSeenMillis }
            when (selectedDate) {
                "Today" -> now.get(Calendar.YEAR) == reviewCal.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == reviewCal.get(Calendar.DAY_OF_YEAR)
                "Yesterday" -> (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.let { it.get(Calendar.YEAR) == reviewCal.get(Calendar.YEAR) && it.get(Calendar.DAY_OF_YEAR) == reviewCal.get(Calendar.DAY_OF_YEAR) }
                "Older" -> reviewCal.before((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })
                else -> true
            }
        }
        val realEvaluation = RoutineDemoModel.evaluateFeedback(filteredReviewHistory)
        val filteredEvents = events.filter { event ->
            val timestamp = event["timestamp"] as? Timestamp ?: return@filter selectedDate == "Older"
            val eventCal = Calendar.getInstance().apply { time = timestamp.toDate() }
            when (selectedDate) {
                "Today" -> now.get(Calendar.YEAR) == eventCal.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == eventCal.get(Calendar.DAY_OF_YEAR)
                "Yesterday" -> (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.let { it.get(Calendar.YEAR) == eventCal.get(Calendar.YEAR) && it.get(Calendar.DAY_OF_YEAR) == eventCal.get(Calendar.DAY_OF_YEAR) }
                "Older" -> {
                    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                    eventCal.before(yesterday)
                }
                else -> true
            }
        }
        if (isLoading && filteredEvents.isEmpty() && filteredReviewHistory.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (filteredEvents.isEmpty() && reviewHistory.isEmpty() && !isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No activity or AI review history recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filteredReviewHistory.isNotEmpty()) {
                    item {
                        Text("AI location history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("A review signal asks you to check context; it does not confirm danger.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        feedbackSyncMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = if (message.startsWith("Saved on this phone")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    }
                    item {
                        AiFeedbackSummaryCard(
                            title = "Parent feedback · real activity",
                            evaluation = realEvaluation,
                            emptyMessage = "No real-activity feedback yet. Mark a record Expected or Needs attention to keep a review tally."
                        )
                    }
                    items(filteredReviewHistory.take(30), key = { "${it.firstSeenMillis}_${it.title}" }) { record ->
                        val reviewKey = "${record.firstSeenMillis}_${record.title}"
                        val isExpanded = expandedReviewKey == reviewKey
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (record.needsReview) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(if (record.needsReview) "Review signal" else "Routine check", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(record.title, fontWeight = FontWeight.SemiBold)
                                Text(SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(record.firstSeenMillis)) +
                                    (if (record.lastSeenMillis > record.firstSeenMillis) " · repeated through " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(record.lastSeenMillis)) else ""), style = MaterialTheme.typography.labelMedium)
                                Text(record.explanation, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = record.feedback == "expected",
                                        onClick = { saveReviewFeedback(record, "expected") },
                                        label = { Text("Expected") }
                                    )
                                    FilterChip(
                                        selected = record.feedback == "concern",
                                        onClick = { saveReviewFeedback(record, "concern") },
                                        label = { Text("Needs attention") }
                                    )
                                }
                                OutlinedButton(onClick = { expandedReviewKey = if (isExpanded) null else reviewKey }) {
                                    Text(if (isExpanded) "Hide flagged location" else "View flagged location")
                                }
                                if (isExpanded && record.latitude.isFinite() && record.longitude.isFinite() && record.latitude in -90.0..90.0 && record.longitude in -180.0..180.0 && (record.latitude != 0.0 || record.longitude != 0.0)) {
                                    OpenStreetMapView(
                                        modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)),
                                        latitude = record.latitude,
                                        longitude = record.longitude,
                                        markerTitle = "AI review location",
                                        defaultZoom = 15.0,
                                        locationZoom = 16.0
                                    )
                                    Text("${String.format(Locale.US, "%.5f", record.latitude)}, ${String.format(Locale.US, "%.5f", record.longitude)}", style = MaterialTheme.typography.labelSmall)
                                } else if (isExpanded) {
                                    Text("No valid coordinates were saved for this review.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item { Text("Recorded trusted-location activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                if (filteredEvents.isEmpty()) {
                    item { Text(if (isLoading) "Loading recorded events…" else "No trusted-location events for this date.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(filteredEvents) { event ->
                        val type = event["eventType"] as? String ?: ""
                        val locId = event["trustedLocationName"] as? String ?: event["trustedLocationId"] as? String ?: "Trusted location"
                        val timestamp = event["timestamp"] as? Timestamp
                        val timeStr = timestamp?.let { SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(it.toDate()) } ?: "Unknown"
                        ActivityHistoryItem(type, locId, timeStr)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiFeedbackSummaryCard(
    title: String,
    evaluation: RoutineDemoModel.FeedbackEvaluation,
    emptyMessage: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (evaluation.labeledSamples == 0) {
                Text(emptyMessage, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${evaluation.labeledSamples} parent labels · ${evaluation.agreementSamples} agree with the current AI label", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Review signals marked Expected: ${evaluation.reviewSignalsMarkedExpected} · routine checks marked Needs attention: ${evaluation.routineChecksMarkedConcern}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Feedback is for prototype evaluation only; it does not retrain the model or establish ground truth.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ActivityHistoryItem(type: String, locId: String, time: String) {
    val icon = if (type == "ENTER") Icons.Default.Login else Icons.AutoMirrored.Filled.Logout
    val color = if (type == "ENTER") Color(0xFF4CAF50) else Color(0xFFF44336)
    val text = if (type == "ENTER") "Entered trusted area" else "Left trusted area"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Location ID: $locId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = time, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}













