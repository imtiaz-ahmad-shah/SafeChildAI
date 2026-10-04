const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

const db = admin.firestore();

/**
 * Generates a 6-digit access code for child linking.
 * Only callable by authenticated parents.
 */
exports.generateChildAccessCode = functions.https.onCall(async (data, context) => {
    console.log("generateChildAccessCode called");

    if (!context.auth) {
        console.error("Unauthenticated call");
        throw new functions.https.HttpsError("unauthenticated", "User must be logged in.");
    }

    const uid = context.auth.uid;

    try {
        // 1. Role check
        const userDoc = await db.collection("users").doc(uid).get();
        if (!userDoc.exists || userDoc.data().role !== "parent") {
            console.error(`User ${uid} is not a parent or doc missing`);
            throw new functions.https.HttpsError("permission-denied", "Only parents can generate codes.");
        }

        // 2. Generate code
        const code = Math.floor(100000 + Math.random() * 900000).toString();
        const expiry = admin.firestore.Timestamp.now().toMillis() + (15 * 60 * 1000);

        const codeData = {
            parentUid: uid,
            parentName: userDoc.data().name || "Parent",
            expiresAt: admin.firestore.Timestamp.fromMillis(expiry),
            status: "active"
        };

        const batch = db.batch();

        // Lookup doc for child
        batch.set(db.collection("linking_codes").doc(code), codeData);

        // Active code ref for parent UI
        batch.set(db.collection("users").doc(uid).collection("linking").doc("active_code"), {
            code: code,
            expiresAt: codeData.expiresAt
        });

        await batch.commit();
        console.log(`Generated code ${code} for parent ${uid}`);

        return { code: code, expiresAt: expiry };
    } catch (error) {
        console.error("Error generating code:", error);
        throw new functions.https.HttpsError("internal", error.message);
    }
});

/**
 * Redeems an access code to link a child to a parent.
 */
exports.redeemChildAccessCode = functions.https.onCall(async (data, context) => {
    console.log("redeemChildAccessCode called");

    if (!context.auth) {
        throw new functions.https.HttpsError("unauthenticated", "User must be logged in.");
    }

    const childUid = context.auth.uid;
    const { code } = data;

    if (!code || code.length !== 6) {
        throw new functions.https.HttpsError("invalid-argument", "Valid 6-digit code required.");
    }

    try {
        const codeRef = db.collection("linking_codes").doc(code);
        const codeDoc = await codeRef.get();

        if (!codeDoc.exists) {
            throw new functions.https.HttpsError("not-found", "Invalid access code.");
        }

        const codeData = codeDoc.data();
        if (codeData.status !== "active" || codeData.expiresAt.toMillis() < Date.now()) {
            throw new functions.https.HttpsError("failed-precondition", "Code is expired or already used.");
        }

        const parentUid = codeData.parentUid;
        const relationshipId = `${parentUid}_${childUid}`;

        const batch = db.batch();

        batch.set(db.collection("relationships").doc(relationshipId), {
            parentUid: parentUid,
            childUid: childUid,
            status: "active",
            createdAt: admin.firestore.FieldValue.serverTimestamp()
        });

        batch.set(db.collection("users").doc(childUid), {
            role: "child",
            parentUid: parentUid,
            linkedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });

        batch.delete(codeRef);
        batch.delete(db.collection("users").doc(parentUid).collection("linking").doc("active_code"));

        await batch.commit();
        console.log(`Successfully linked child ${childUid} to parent ${parentUid}`);

        return { success: true, parentName: codeData.parentName };
    } catch (error) {
        console.error("Error redeeming code:", error);
        throw new functions.https.HttpsError("internal", error.message);
    }
});
