const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write profiles, directory, contacts, or messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("directory").doc("ayazbugti786").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("contacts").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Hacker",
      username: "hacker",
      bio: "Bio",
      dob: "2000-01-01",
      provider: "username",
      online: true,
      verified: false,
      premiumUnlocked: false,
      accountStatus: "active",
      reportCount: 0,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Authenticated owner can create and update UserProfile; cannot self-alter reportCount or accountStatus", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Ayaz Bugti",
      username: "ayazbugti786",
      bio: "Dream Big...",
      dob: "2000-01-01",
      provider: "google",
      online: true,
      verified: false,
      premiumUnlocked: true,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      bio: "Building Nomi Chat",
      dob: "1999-05-12",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Rejects client attempting to forge reportCount or accountStatus
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      accountStatus: "banned",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Cross-user isolation prevents Bob from reading or modifying Alice's profile, contacts, and messages", async () => {
  const created = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Ayaz Bugti",
      username: "ayazbugti786",
      bio: "Dream Big...",
      dob: "2000-01-01",
      provider: "google",
      online: true,
      verified: false,
      premiumUnlocked: true,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    });
    await db.collection("users").doc(ALICE_UID).collection("contacts").doc("c1").set({
      userId: ALICE_UID,
      name: "Sara Khan",
      username: "sarakhan",
      phone: "+92 321 9876543",
      threadType: "chat",
      online: true,
      typing: false,
      unreadCount: 1,
      lastMessageText: "Okay",
      lastMessageFromMe: false,
      lastMessageTimeLabel: "7:30 PM",
      pinned: false,
      accountStatus: "active",
      reportCount: 0,
      reportedBy: [],
      createdAt: created,
      updatedAt: created,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("contacts").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("contacts").doc("c1").update({
      typing: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Directory reporting prevents duplicates, blocks forged bans, and auto-bans at 10 unique reporters", async () => {
  const created = new Date(Date.now() - 10000);
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    bobDb.collection("directory").doc("target_user").set({
      ownerUid: BOB_UID,
      username: "target_user",
      displayName: "Target User",
      provider: "username",
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Owner can update displayName and online presence without altering moderation fields
  await assertSucceeds(
    bobDb.collection("directory").doc("target_user").update({
      online: true,
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Overwriting an existing directory username by another user must fail
  const charlieDb = testEnv.authenticatedContext("charlie_uid").firestore();
  await assertFails(
    charlieDb.collection("directory").doc("target_user").update({
      displayName: "Hijacked",
      online: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // List scraping on directory must fail
  await assertFails(bobDb.collection("directory").get());

  // Forged ban with only 1 reporter must fail
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("directory").doc("target_user").update({
      reportedByUids: [ALICE_UID],
      reportCount: 1,
      accountStatus: "banned",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // 9 unique reporters keep status active
  const reporters = [];
  for (let i = 1; i <= 9; i++) {
    const uid = `reporter_${i}`;
    reporters.push(uid);
    const rDb = testEnv.authenticatedContext(uid).firestore();
    await assertSucceeds(
      rDb.collection("directory").doc("target_user").update({
        reportedByUids: [...reporters],
        reportCount: reporters.length,
        accountStatus: "active",
        updatedAt: new Date(Date.now() - 1000),
      })
    );
  }

  // Duplicate report from reporter_1 must fail
  const r1Db = testEnv.authenticatedContext("reporter_1").firestore();
  await assertFails(
    r1Db.collection("directory").doc("target_user").update({
      reportedByUids: [...reporters, "reporter_1"],
      reportCount: 10,
      accountStatus: "banned",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // 10th unique reporter triggers auto-ban!
  const r10Db = testEnv.authenticatedContext("reporter_10").firestore();
  await assertSucceeds(
    r10Db.collection("directory").doc("target_user").update({
      reportedByUids: [...reporters, "reporter_10"],
      reportCount: 10,
      accountStatus: "banned",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Contact thread typing indicator, message creation, and report tracking work for owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);

  const batch = aliceDb.batch();
  const contactRef = aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc("c_sara");
  const msgRef = contactRef.collection("messages").doc("m_init");

  batch.set(contactRef, {
    userId: ALICE_UID,
    name: "Sara Khan",
    username: "sarakhan",
    phone: "+92 321 9876543",
    threadType: "chat",
    online: true,
    typing: false,
    unreadCount: 1,
    lastMessageText: "Okay",
    lastMessageFromMe: false,
    lastMessageTimeLabel: "7:30 PM",
    pinned: false,
    accountStatus: "active",
    reportCount: 0,
    reportedBy: [],
    createdAt: created,
    updatedAt: created,
  });

  batch.set(msgRef, {
    userId: ALICE_UID,
    contactId: "c_sara",
    sender: "them",
    text: "Okay",
    timeLabel: "7:30 PM",
    aiGenerated: false,
    createdAt: created,
  });

  await assertSucceeds(batch.commit());

  await assertSucceeds(
    contactRef.update({
      typing: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    contactRef.update({
      typing: false,
      reportedBy: ["ayazbugti786"],
      reportCount: 1,
      accountStatus: "active",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Credentials subcollection enforces owner-only access and valid salted SHA-256 hash length", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const sampleHash = "a".repeat(64);
  const sampleSalt = "b".repeat(32);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("credentials").doc("alice_99").set({
      userId: ALICE_UID,
      username: "alice_99",
      displayName: "Alice Tester",
      dob: "2000-01-01",
      passwordSalt: sampleSalt,
      passwordHash: sampleHash,
      accountStatus: "active",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Cross-user read/list must fail
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("credentials").doc("alice_99").get());
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("credentials").get());
});

test("Directory supports owner photoData sync and Stories enforce 24h validity and owner-only deletion", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("directory").doc("alice_photo").set({
      ownerUid: ALICE_UID,
      username: "alice_photo",
      displayName: "Alice",
      provider: "username",
      photoData: "data:image/jpeg;base64,AAA",
      online: true,
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("directory").doc("alice_photo").update({
      photoData: "data:image/jpeg;base64,BBB",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  const nowMs = Date.now();
  await assertSucceeds(
    aliceDb.collection("stories").doc("story_1").set({
      userId: ALICE_UID,
      authorName: "Alice",
      authorUsername: "alice_photo",
      authorPhotoData: "data:image/jpeg;base64,BBB",
      mediaData: "data:image/jpeg;base64,STORY",
      caption: "Hello 24h",
      storyFont: "noto_nastaliq",
      createdAtMs: nowMs,
      expiresAtMs: nowMs + 86400000,
      createdAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("stories").doc("story_text_urdu").set({
      userId: ALICE_UID,
      authorName: "Alice",
      authorUsername: "alice_photo",
      mediaData: "text_card:emerald",
      caption: "السلام علیکم Nomi",
      storyFont: "gulzar",
      createdAtMs: nowMs,
      expiresAtMs: nowMs + 86400000,
      createdAt: created,
    })
  );

  // Exceeding 24 hours must fail
  await assertFails(
    aliceDb.collection("stories").doc("story_too_long").set({
      userId: ALICE_UID,
      authorName: "Alice",
      authorUsername: "alice_photo",
      mediaData: "data:image/jpeg;base64,STORY",
      caption: "Too long",
      createdAtMs: nowMs,
      expiresAtMs: nowMs + 86400001,
      createdAt: created,
    })
  );

  await assertFails(unauthDb.collection("stories").doc("story_1").get());
  await assertSucceeds(bobDb.collection("stories").doc("story_1").get());
  await assertFails(bobDb.collection("stories").doc("story_1").delete());
  await assertSucceeds(aliceDb.collection("stories").doc("story_1").delete());
});

test("Owner can set/update bannerData on profile, directory, and contacts while blocking non-owners and oversized banners", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const validBanner = "data:image/jpeg;base64,BANNER_VALID";
  const oversizedBanner = "x".repeat(150001);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      username: "alice_banner",
      bio: "Bio",
      dob: "2000-01-01",
      provider: "username",
      bannerData: validBanner,
      online: true,
      verified: false,
      premiumUnlocked: false,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("directory").doc("alice_banner").set({
      ownerUid: ALICE_UID,
      username: "alice_banner",
      displayName: "Alice",
      provider: "username",
      bannerData: validBanner,
      online: true,
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("directory").doc("alice_banner").update({
      bannerData: "data:image/jpeg;base64,BANNER_UPDATED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(
    bobDb.collection("directory").doc("alice_banner").update({
      bannerData: "data:image/jpeg;base64,HIJACKED",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      bannerData: oversizedBanner,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("contacts").doc("c_alice").set({
      userId: BOB_UID,
      name: "Alice",
      username: "alice_banner",
      phone: "",
      threadType: "chat",
      bannerData: "data:image/jpeg;base64,BANNER_UPDATED",
      online: true,
      typing: false,
      unreadCount: 0,
      lastMessageText: "",
      lastMessageFromMe: false,
      lastMessageTimeLabel: "",
      pinned: false,
      peerUid: ALICE_UID,
      isRegisteredPeer: true,
      accountStatus: "active",
      reportCount: 0,
      reportedBy: [],
      createdAt: created,
      updatedAt: created,
    })
  );
});

test("Chat settings (mute, theme, disappearing) and voice message relay enforce schema and size bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const contactRef = aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc("c_bob");

  await assertSucceeds(
    contactRef.set({
      userId: ALICE_UID,
      name: "Bob",
      username: "bob_user",
      phone: "@bob_user",
      threadType: "chat",
      online: false,
      typing: false,
      unreadCount: 0,
      lastMessageText: "",
      lastMessageFromMe: false,
      lastMessageTimeLabel: "",
      pinned: false,
      muted: false,
      disappearingSeconds: 0,
      chatTheme: "classic",
      peerUid: BOB_UID,
      isRegisteredPeer: true,
      accountStatus: "active",
      reportCount: 0,
      reportedBy: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    contactRef.update({
      muted: true,
      disappearingSeconds: 86400,
      chatTheme: "ocean",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    contactRef.collection("messages").doc("m_voice_1").set({
      userId: ALICE_UID,
      contactId: "c_bob",
      sender: "me",
      text: "🎤 Voice message (0:05)",
      messageType: "voice",
      mediaData: "data:audio/mp4;base64,AAAA",
      durationSec: 5,
      expiresAtMs: Date.now() + 86400000,
      timeLabel: "8:00 PM",
      aiGenerated: false,
      createdAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("direct_messages").doc("dm_voice_1").set({
      fromUid: ALICE_UID,
      toUid: BOB_UID,
      fromUsername: "alice_user",
      fromDisplayName: "Alice",
      toUsername: "bob_user",
      text: "🎤 Voice message (0:05)",
      messageType: "voice",
      mediaData: "data:audio/mp4;base64,AAAA",
      durationSec: 5,
      timeLabel: "8:00 PM",
      createdAt: created,
    })
  );

  // Oversized voice mediaData (> 240000) or invalid messageType must fail
  await assertFails(
    aliceDb.collection("direct_messages").doc("dm_voice_oversized").set({
      fromUid: ALICE_UID,
      toUid: BOB_UID,
      fromUsername: "alice_user",
      fromDisplayName: "Alice",
      toUsername: "bob_user",
      text: "Too big",
      messageType: "voice",
      mediaData: "a".repeat(240001),
      durationSec: 5,
      timeLabel: "8:00 PM",
      createdAt: created,
    })
  );

  await assertFails(
    contactRef.collection("messages").doc("m_bad_type").set({
      userId: ALICE_UID,
      contactId: "c_bob",
      sender: "me",
      text: "Invalid type",
      messageType: "exe",
      timeLabel: "8:00 PM",
      aiGenerated: false,
      createdAt: created,
    })
  );
});

test("Profile privacy settings (profileVisibility, allowedViewerUids, maxProfileViewers, bio) validate cleanly and block invalid values", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      username: "alice_priv",
      bio: "Private bio",
      dob: "2000-01-01",
      provider: "username",
      profileVisibility: "everyone",
      allowedViewerUids: [],
      maxProfileViewers: 0,
      online: true,
      verified: false,
      premiumUnlocked: false,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("directory").doc("alice_priv").set({
      ownerUid: ALICE_UID,
      username: "alice_priv",
      displayName: "Alice",
      bio: "Private bio",
      provider: "username",
      profileVisibility: "selected",
      allowedViewerUids: [BOB_UID],
      maxProfileViewers: 2,
      online: true,
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      profileVisibility: "nobody",
      allowedViewerUids: [BOB_UID],
      maxProfileViewers: 1,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Invalid visibility enum must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      profileVisibility: "friends_of_friends",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Negative or oversized maxProfileViewers must fail
  await assertFails(
    aliceDb.collection("directory").doc("alice_priv").update({
      maxProfileViewers: 501,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Contact bio is allowed cleanly
  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("contacts").doc("c_alice_p").set({
      userId: BOB_UID,
      name: "Alice",
      username: "alice_priv",
      phone: "@alice_priv",
      threadType: "chat",
      bio: "Private bio",
      online: true,
      typing: false,
      unreadCount: 0,
      lastMessageText: "",
      lastMessageFromMe: false,
      lastMessageTimeLabel: "",
      pinned: false,
      peerUid: ALICE_UID,
      isRegisteredPeer: true,
      archived: false,
      accountStatus: "active",
      reportCount: 0,
      reportedBy: [],
      createdAt: created,
      updatedAt: created,
    })
  );
});

test("Chat archiving allows owner boolean archived updates and rejects invalid types or cross-user access", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 2000);
  const contactRef = aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc("c_arch");

  await assertSucceeds(
    contactRef.set({
      userId: ALICE_UID,
      name: "Bob",
      username: "bob",
      phone: "@bob",
      threadType: "chat",
      online: false,
      typing: false,
      unreadCount: 0,
      lastMessageText: "",
      lastMessageFromMe: false,
      lastMessageTimeLabel: "",
      pinned: false,
      archived: false,
      accountStatus: "active",
      reportCount: 0,
      reportedBy: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    contactRef.update({
      archived: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(
    contactRef.update({
      archived: "yes",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("contacts").doc("c_arch").update({
      archived: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Messages support reply quotes and owner-only permanent deletion; Directory supports owner-only handle release", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 3000);

  // Set up contact and message with quoted reply
  const contactRef = aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc("c_reply_test");
  const msgRef = contactRef.collection("messages").doc("m_reply_1");

  await assertSucceeds(
    contactRef.set({
      userId: ALICE_UID,
      name: "Sara",
      username: "sara",
      phone: "@sara",
      threadType: "chat",
      online: true,
      typing: false,
      unreadCount: 0,
      lastMessageText: "Sure thing!",
      lastMessageFromMe: true,
      lastMessageTimeLabel: "8:00 PM",
      pinned: false,
      accountStatus: "active",
      reportCount: 0,
      reportedBy: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Message with reply fields
  await assertSucceeds(
    msgRef.set({
      userId: ALICE_UID,
      contactId: "c_reply_test",
      sender: "me",
      text: "Sure thing!",
      replyToText: "Are you coming today?",
      replyToSender: "Sara",
      replyToMessageId: "m_prev_123",
      timeLabel: "8:00 PM",
      aiGenerated: false,
      createdAt: created,
    })
  );

  // Non-owner cannot delete Alice's message
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("contacts").doc("c_reply_test").collection("messages").doc("m_reply_1").delete());

  // Owner can permanently delete own message
  await assertSucceeds(msgRef.delete());

  // Directory entry creation by owner
  const dirRef = aliceDb.collection("directory").doc("alice_handle_old");
  await assertSucceeds(
    dirRef.set({
      ownerUid: ALICE_UID,
      username: "alice_handle_old",
      displayName: "Alice Old",
      provider: "google",
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Non-owner cannot delete Alice's handle from directory
  await assertFails(bobDb.collection("directory").doc("alice_handle_old").delete());

  // Owner can delete / release old username handle
  await assertSucceeds(dirRef.delete());

  // Direct message relay with reply fields
  const dmRef = aliceDb.collection("direct_messages").doc("dm_reply_1");
  await assertSucceeds(
    dmRef.set({
      fromUid: ALICE_UID,
      toUid: BOB_UID,
      fromUsername: "alice",
      fromDisplayName: "Alice",
      toUsername: "bob",
      text: "I agree with your message",
      messageType: "text",
      replyToText: "What do you think?",
      replyToSender: "Bob",
      replyToMessageId: "dm_orig_99",
      timeLabel: "8:01 PM",
      createdAt: created,
    })
  );

  // Bob (toUid) can delete the relayed direct message after receipt
  await assertSucceeds(bobDb.collection("direct_messages").doc("dm_reply_1").delete());
});

test("Stories support rich editor metadata, real-time views/likes/reactions, and owner-only views listing", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext("charlie_uid").firestore();
  const created = new Date(Date.now() - 4000);
  const nowMs = Date.now();

  const storyRef = aliceDb.collection("stories").doc("story_rich_1");
  await assertSucceeds(
    storyRef.set({
      userId: ALICE_UID,
      authorName: "Alice",
      authorUsername: "alice",
      mediaData: "data:video/mp4;base64,AAAAIGZ0eXBpc29t",
      mediaType: "video",
      caption: "Sunset @bob!",
      storyFont: "noto_nastaliq",
      musicTag: "Chill Lo-Fi • Nomi Beats",
      overlayText: "Hello World",
      overlayColorHex: 0xFFFFFFFF,
      overlayYFraction: 0.42,
      stickersJson: "[{\"id\":\"s1\",\"text\":\"✨\",\"x\":0.5,\"y\":0.3}]",
      drawingJson: "[{\"c\":4294967295,\"w\":8.0,\"p\":[[0.1,0.2],[0.3,0.4]]}]",
      cropMode: "crop",
      rotationDegrees: 0,
      viewedByUids: [],
      likedByUids: [],
      createdAtMs: nowMs,
      expiresAtMs: nowMs + 86400000,
      createdAt: created,
    })
  );

  // Bob opens the story -> records view in viewedByUids and /views/bob
  await assertSucceeds(
    bobDb.collection("stories").doc("story_rich_1").update({
      viewedByUids: [BOB_UID],
    })
  );

  await assertSucceeds(
    bobDb.collection("stories").doc("story_rich_1").collection("views").doc(BOB_UID).set({
      viewerUid: BOB_UID,
      viewerName: "Bob",
      viewerUsername: "bob",
      liked: true,
      reactionEmoji: "🔥",
      viewedAtMs: nowMs + 1000,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob toggles like on the story doc
  await assertSucceeds(
    bobDb.collection("stories").doc("story_rich_1").update({
      likedByUids: [BOB_UID],
    })
  );

  // Charlie cannot forge Bob's view or alter story caption
  await assertFails(
    charlieDb.collection("stories").doc("story_rich_1").update({
      caption: "Hacked caption",
    })
  );
  await assertFails(
    charlieDb.collection("stories").doc("story_rich_1").collection("views").doc(BOB_UID).set({
      viewerUid: BOB_UID,
      viewerName: "Bob",
      viewerUsername: "bob",
      liked: false,
      reactionEmoji: "",
      viewedAtMs: nowMs + 1500,
      updatedAt: new Date(Date.now() - 300),
    })
  );

  // Non-owner cannot list everyone who viewed Alice's story
  await assertFails(bobDb.collection("stories").doc("story_rich_1").collection("views").get());

  // Owner Alice can list all viewers of her story
  await assertSucceeds(aliceDb.collection("stories").doc("story_rich_1").collection("views").get());
});

test("User profile and directory enforce showOnlineStatus boolean privacy rules", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 4000);

  const userRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(
    userRef.set({
      userId: ALICE_UID,
      displayName: "Alice",
      username: "alice_privacy",
      bio: "Hello",
      dob: "2000-01-01",
      provider: "google",
      online: true,
      showOnlineStatus: true,
      verified: false,
      premiumUnlocked: true,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  const dirRef = aliceDb.collection("directory").doc("alice_privacy");
  await assertSucceeds(
    dirRef.set({
      ownerUid: ALICE_UID,
      username: "alice_privacy",
      displayName: "Alice",
      provider: "google",
      online: true,
      showOnlineStatus: true,
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Owner can toggle showOnlineStatus off on both user profile and directory
  await assertSucceeds(
    userRef.update({
      showOnlineStatus: false,
      online: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    dirRef.update({
      showOnlineStatus: false,
      online: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject non-boolean showOnlineStatus
  await assertFails(
    userRef.update({
      showOnlineStatus: "hidden",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    dirRef.update({
      showOnlineStatus: "hidden",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Non-owner cannot alter Alice's showOnlineStatus
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      showOnlineStatus: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("directory").doc("alice_privacy").update({
      showOnlineStatus: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Username reservations enforce uniqueness, allow reclaiming retired unowned handles (A -> B -> A), and deny active theft or deletion", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  // Unauthenticated cannot read or write /usernames
  await assertFails(unauthDb.collection("usernames").doc("alice_old").get());
  await assertFails(aliceDb.collection("usernames").get()); // list scraping blocked

  // Alice claims alice_old
  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_old").set({
      username: "alice_old",
      ownerUid: ALICE_UID,
      status: "active",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Bob cannot steal or overwrite active alice_old
  await assertFails(
    bobDb.collection("usernames").doc("alice_old").set({
      username: "alice_old",
      ownerUid: BOB_UID,
      status: "active",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    bobDb.collection("usernames").doc("alice_old").update({
      status: "retired",
      ownerUid: "",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Alice changes username to alice_new and retires alice_old
  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_new").set({
      username: "alice_new",
      ownerUid: ALICE_UID,
      status: "active",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_old").update({
      status: "retired",
      ownerUid: "",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Alice dislikes alice_new and smoothly switches back to her previous username alice_old
  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_old").update({
      status: "active",
      ownerUid: ALICE_UID,
      updatedAt: new Date(Date.now() - 200),
    })
  );
  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_new").update({
      status: "retired",
      ownerUid: "",
      updatedAt: new Date(Date.now() - 150),
    })
  );

  // Now that alice_old is active again, Bob cannot claim or overwrite it
  await assertFails(
    bobDb.collection("usernames").doc("alice_old").update({
      status: "active",
      ownerUid: BOB_UID,
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Deletion of username reservation documents remains forbidden
  await assertFails(aliceDb.collection("usernames").doc("alice_old").delete());
});

test("Public profiles allow authenticated single-doc get, block list scraping, and restrict writes to owner or verified admin", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("public_profiles").doc(ALICE_UID).set({
      ownerUid: ALICE_UID,
      username: "ayaz_bugti",
      displayName: "Ayaz Bugti",
      bio: "Building Apps & Creating Digital ...",
      photoData: "data:image/jpeg;base64,PHOTO",
      bannerData: "data:image/jpeg;base64,BANNER",
      online: true,
      showOnlineStatus: true,
      accountStatus: "active",
      bannedByAdmin: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Unauthenticated reads fail; authenticated get succeeds; list scraping fails
  await assertFails(unauthDb.collection("public_profiles").doc(ALICE_UID).get());
  await assertSucceeds(bobDb.collection("public_profiles").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("public_profiles").get());

  // Owner can update username and profile details without affecting admin status
  await assertSucceeds(
    aliceDb.collection("public_profiles").doc(ALICE_UID).update({
      username: "ayaz_bugti_v2",
      displayName: "Ayaz Updated",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Regular user cannot self-ban or tamper with another user's public profile
  await assertFails(
    aliceDb.collection("public_profiles").doc(ALICE_UID).update({
      accountStatus: "banned",
      bannedByAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("public_profiles").doc(ALICE_UID).update({
      displayName: "Hacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Admin Gmail lock restricts admin_config and moderation_alerts exclusively to verified ayazbugti100@gmail.com and supports Ignore, Ban, and Recovery", async () => {
  const ADMIN_UID = "admin_ayaz_uid";
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "ayazbugti100@gmail.com",
    email_verified: true,
  }).firestore();
  const unverifiedAdminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "ayazbugti100@gmail.com",
    email_verified: false,
  }).firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@gmail.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@gmail.com",
    email_verified: true,
  }).firestore();
  const created = new Date(Date.now() - 6000);

  // Regular user and unverified email cannot create or read admin_config
  await assertFails(
    aliceDb.collection("admin_config").doc("primary").set({
      lockedGmail: "ayazbugti100@gmail.com",
      adminUid: ALICE_UID,
      enabled: true,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertFails(
    unverifiedAdminDb.collection("admin_config").doc("primary").set({
      lockedGmail: "ayazbugti100@gmail.com",
      adminUid: ADMIN_UID,
      enabled: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Verified ayazbugti100@gmail.com can persist admin_config permanently and cannot delete it
  await assertSucceeds(
    adminDb.collection("admin_config").doc("primary").set({
      lockedGmail: "ayazbugti100@gmail.com",
      adminUid: ADMIN_UID,
      enabled: true,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    adminDb.collection("admin_config").doc("primary").update({
      lockedGmail: "ayazbugti100@gmail.com",
      adminUid: ADMIN_UID,
      enabled: true,
      updatedAt: new Date(Date.now() - 2000),
    })
  );
  await assertFails(aliceDb.collection("admin_config").doc("primary").get());
  await assertFails(adminDb.collection("admin_config").doc("primary").delete());

  // Set up Bob's profile, public_profile, and directory entry (0 reports)
  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob Target",
      username: "bob_target",
      bio: "Bio",
      dob: "1998-02-02",
      provider: "google",
      online: true,
      verified: false,
      premiumUnlocked: false,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    bobDb.collection("public_profiles").doc(BOB_UID).set({
      ownerUid: BOB_UID,
      username: "bob_target",
      displayName: "Bob Target",
      online: true,
      accountStatus: "active",
      bannedByAdmin: false,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    bobDb.collection("directory").doc("bob_target").set({
      ownerUid: BOB_UID,
      username: "bob_target",
      displayName: "Bob Target",
      provider: "google",
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      bannedByAdmin: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Alice submits a moderation alert against Bob
  const alertRef = aliceDb.collection("moderation_alerts").doc("alert_bob_1");
  await assertSucceeds(
    alertRef.set({
      reportedUid: BOB_UID,
      reportedUsername: "bob_target",
      reportedDisplayName: "Bob Target",
      reporterUid: ALICE_UID,
      reporterUsername: "alice",
      reason: "Abuse",
      status: "pending",
      createdAtMs: Date.now() - 1000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Regular users cannot read, list, update, or delete moderation_alerts
  await assertFails(aliceDb.collection("moderation_alerts").doc("alert_bob_1").get());
  await assertFails(aliceDb.collection("moderation_alerts").get());
  await assertFails(
    aliceDb.collection("moderation_alerts").doc("alert_bob_1").update({
      status: "banned",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Verified admin can list and read alerts, and Ignore/Ban/Recover Bob directly across DB collections
  await assertSucceeds(adminDb.collection("moderation_alerts").get());

  // 1) Ignore action
  await assertSucceeds(
    adminDb.collection("moderation_alerts").doc("alert_bob_1").update({
      status: "ignored",
      adminEmail: "ayazbugti100@gmail.com",
      resolvedAtMs: Date.now(),
      updatedAt: new Date(Date.now() - 400),
    })
  );

  // 2) Ban action even with < 10 reports
  const adminBatchBan = adminDb.batch();
  const banTime = new Date(Date.now() - 300);
  adminBatchBan.update(adminDb.collection("moderation_alerts").doc("alert_bob_1"), {
    status: "banned",
    adminEmail: "ayazbugti100@gmail.com",
    resolvedAtMs: Date.now(),
    updatedAt: banTime,
  });
  adminBatchBan.update(adminDb.collection("users").doc(BOB_UID), {
    accountStatus: "banned",
    bannedByAdmin: true,
    updatedAt: banTime,
  });
  adminBatchBan.update(adminDb.collection("public_profiles").doc(BOB_UID), {
    accountStatus: "banned",
    bannedByAdmin: true,
    updatedAt: banTime,
  });
  adminBatchBan.update(adminDb.collection("directory").doc("bob_target"), {
    accountStatus: "banned",
    bannedByAdmin: true,
    updatedAt: banTime,
  });
  await assertSucceeds(adminBatchBan.commit());

  // 3) Recovery / Unban action restoring Bob cleanly
  const adminBatchRecover = adminDb.batch();
  const recoverTime = new Date(Date.now() - 100);
  adminBatchRecover.update(adminDb.collection("moderation_alerts").doc("alert_bob_1"), {
    status: "recovered",
    adminEmail: "ayazbugti100@gmail.com",
    resolvedAtMs: Date.now(),
    updatedAt: recoverTime,
  });
  adminBatchRecover.update(adminDb.collection("users").doc(BOB_UID), {
    accountStatus: "active",
    reportCount: 0,
    bannedByAdmin: false,
    updatedAt: recoverTime,
  });
  adminBatchRecover.update(adminDb.collection("public_profiles").doc(BOB_UID), {
    accountStatus: "active",
    bannedByAdmin: false,
    updatedAt: recoverTime,
  });
  adminBatchRecover.update(adminDb.collection("directory").doc("bob_target"), {
    accountStatus: "active",
    reportCount: 0,
    reportedByUids: [],
    bannedByAdmin: false,
    updatedAt: recoverTime,
  });
  await assertSucceeds(adminBatchRecover.commit());
});

test("Verified blue badge (verified: true) is strictly bound to verified ayazbugti100@gmail.com across users, directory, and public_profiles", async () => {
  const created = new Date(Date.now() - 5000);
  const ADMIN_UID = "admin_ayaz_uid";
  const NEW_USER_UID = "new_user_uid";

  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "ayazbugti100@gmail.com",
    email_verified: true,
  }).firestore();

  const unverifiedAdminDb = testEnv.authenticatedContext("unverified_admin_uid", {
    email: "ayazbugti100@gmail.com",
    email_verified: false,
  }).firestore();

  const otherDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@gmail.com",
    email_verified: true,
  }).firestore();

  const newUserDb = testEnv.authenticatedContext(NEW_USER_UID).firestore();

  // Verified ayazbugti100@gmail.com CAN create & maintain verified: true on users, directory, and public_profiles
  await assertSucceeds(
    adminDb.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      displayName: "Ayaz Bugti",
      username: "ayaz_bugti",
      email: "ayazbugti100@gmail.com",
      bio: "Admin",
      dob: "1995-08-08",
      provider: "google",
      online: true,
      verified: true,
      premiumUnlocked: true,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    adminDb.collection("directory").doc("ayaz_bugti").set({
      ownerUid: ADMIN_UID,
      username: "ayaz_bugti",
      displayName: "Ayaz Bugti",
      provider: "google",
      online: true,
      verified: true,
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    adminDb.collection("public_profiles").doc(ADMIN_UID).set({
      ownerUid: ADMIN_UID,
      username: "ayaz_bugti",
      displayName: "Ayaz Bugti",
      online: true,
      verified: true,
      accountStatus: "active",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Unverified ayazbugti100@gmail.com CANNOT set verified: true
  await assertFails(
    unverifiedAdminDb.collection("users").doc("unverified_admin_uid").set({
      userId: "unverified_admin_uid",
      displayName: "Fake",
      username: "fake_ayaz",
      bio: "No",
      dob: "1995-08-08",
      provider: "google",
      online: true,
      verified: true,
      premiumUnlocked: false,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Other verified Gmail (bob@gmail.com) CANNOT create verified: true on users, directory, or public_profiles
  await assertFails(
    otherDb.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      username: "bob_verified_attempt",
      email: "bob@gmail.com",
      bio: "Bio",
      dob: "2000-01-01",
      provider: "google",
      online: true,
      verified: true,
      premiumUnlocked: false,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertFails(
    otherDb.collection("directory").doc("bob_verified_attempt").set({
      ownerUid: BOB_UID,
      username: "bob_verified_attempt",
      displayName: "Bob",
      provider: "google",
      online: true,
      verified: true,
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertFails(
    otherDb.collection("public_profiles").doc(BOB_UID).set({
      ownerUid: BOB_UID,
      username: "bob_verified_attempt",
      displayName: "Bob",
      online: true,
      verified: true,
      accountStatus: "active",
      createdAt: created,
      updatedAt: created,
    })
  );

  // New regular user CAN create with verified: false, and CANNOT escalate to verified: true on update
  await assertSucceeds(
    newUserDb.collection("users").doc(NEW_USER_UID).set({
      userId: NEW_USER_UID,
      displayName: "New User",
      username: "new_user_1",
      bio: "Hi",
      dob: "2002-02-02",
      provider: "google",
      online: true,
      verified: false,
      premiumUnlocked: false,
      accountStatus: "active",
      reportCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    newUserDb.collection("directory").doc("new_user_1").set({
      ownerUid: NEW_USER_UID,
      username: "new_user_1",
      displayName: "New User",
      provider: "google",
      online: true,
      verified: false,
      accountStatus: "active",
      reportCount: 0,
      reportedByUids: [],
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    newUserDb.collection("public_profiles").doc(NEW_USER_UID).set({
      ownerUid: NEW_USER_UID,
      username: "new_user_1",
      displayName: "New User",
      online: true,
      verified: false,
      accountStatus: "active",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Escalation attempts MUST fail
  await assertFails(
    newUserDb.collection("users").doc(NEW_USER_UID).update({
      verified: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    newUserDb.collection("directory").doc("new_user_1").update({
      verified: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    newUserDb.collection("public_profiles").doc(NEW_USER_UID).update({
      verified: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});



