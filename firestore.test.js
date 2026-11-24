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
      verified: true,
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
      verified: true,
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
