# Task 1 Report: Core Library Layer — Group Story Support

## Changes

### `lib/src/main/java/org/asamk/signal/manager/Manager.java`
- `sendStory(String, boolean)` → `sendStory(String attachment, boolean allowsReplies, Optional<GroupId> groupId)`.
- Throws clause extended with `GroupNotFoundException, NotAGroupMemberException`.
- All required imports (`Optional`, `GroupId`, `GroupNotFoundException`, `NotAGroupMemberException`) were already present in the file, so no import changes were needed.
- Updated the javadoc above the method to describe the new `groupId` parameter.

### `lib/src/main/java/org/asamk/signal/manager/internal/ManagerImpl.java`
- `sendStory()` now takes the third `Optional<GroupId> groupId` parameter and throws `GroupNotFoundException, NotAGroupMemberException` in addition to the existing exceptions.
- MIME validation runs first (shared by both paths); if `groupId.isPresent()`, control is handed off to a new private `sendGroupStory(String attachment, boolean allowsReplies, GroupId groupId)` method before any attachment upload happens (fail-fast).
- The pre-existing "My Story" branch (`groupId.isEmpty()`) is untouched — same code, same order of operations.
- New private `sendGroupStory` method:
  1. Resolves the group via `context.getGroupHelper().getGroup(groupId)`; throws `GroupNotFoundException` if null.
  2. Checks `groupInfo.isMember(account.getSelfRecipientId())`; throws `NotAGroupMemberException` if false.
  3. Requires a V2 group via pattern-match cast to `GroupInfoV2`; throws `IOException("Stories are only supported for V2 groups")` otherwise.
  4. Uploads the attachment, builds a `SignalServiceGroupV2` context (master key + revision, revision 0 if `getGroup()` is null), builds the `SignalServiceStoryMessage.forFileAttachment(...)` with the group context.
  5. Uses `getNextMessageTimestamp()` for the timestamp (not `System.currentTimeMillis()`).
  6. Calls `context.getSendHelper().sendGroupStoryMessage(storyMessage, timestamp, groupInfoV2, allowsReplies)`.
  7. Builds sync transcript recipients using `groupInfoV2.getDistributionId().asUuid().toString()` (not `DistributionId.MY_STORY`).
  8. Sends the sync message via `dependencies.getMessageSender().sendStorySyncMessage(...)`, wrapping `UntrustedIdentityException` in `IOException`, same as the My Story path.
  9. Returns `SendMessageResults` built the same way as the My Story path.
- Added imports: `org.asamk.signal.manager.storage.groups.GroupInfoV2` and `org.whispersystems.signalservice.api.messages.SignalServiceGroupV2`.

### `lib/src/main/java/org/asamk/signal/manager/helper/SendHelper.java`
- New public method `sendGroupStoryMessage(SignalServiceStoryMessage storyMessage, long timestamp, GroupInfoV2 groupInfo, boolean allowsReplies) throws IOException`, inserted right after the existing `sendStoryMessage` (My Story) method.
- Flow follows the brief exactly:
  1. `groupInfo.getMembersWithout(account.getSelfRecipientId())` for recipients, converted to `List.copyOf(...)`.
  2. Resolves addresses via `context.getRecipientHelper()::resolveSignalServiceAddress` and unidentified access via `context.getUnidentifiedAccessHelper().getAccessFor(recipientIds)`.
  3. Calls the existing private `getGroupSendEndorsements(groupInfo)`; throws `IOException("Group send endorsements unavailable; try again after group state refreshes")` if the result is null.
  4. Derives `GroupSecretParams.deriveFromMasterKey(groupInfo.getMasterKey())` and fetches the sender certificate via `context.getUnidentifiedAccessHelper().getSenderCertificateFor(null)`.
  5. Builds `GroupSendEndorsements` with an `ACI`-keyed map derived from the resolved addresses.
  6. Builds `SignalServiceStoryMessageRecipient`s using `groupInfo.getDistributionId().asUuid().toString()`.
  7. Calls `messageSender.sendGroupStory(groupInfo.getDistributionId(), Optional.of(groupInfo.getMasterKey().serialize()), addresses, unidentifiedAccesses, groupSendEndorsements, false, storyMessage, timestamp, storyMessageRecipients, null)`, catching `UntrustedIdentityException | InvalidKeyException | NoSessionException | InvalidRegistrationIdException` and rethrowing as `IOException`, mirroring `sendStoryMessage`'s existing catch clause.
  8. Calls `handleSendMessageResult(r)` for each result and returns the list.
- No new imports were required — `GroupInfoV2`, `GroupSecretParams`, `ACI`, `GroupSendEndorsements`, and `UnidentifiedAccess` were already imported in this file (used by the existing group-message sending code).

## Build output

```
$ export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
$ ./gradlew :lib:compileJava 2>&1 | tail -10
> Task :buildSrc:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :buildSrc:compileKotlin UP-TO-DATE
> Task :buildSrc:compileJava NO-SOURCE
> Task :buildSrc:compileGroovy NO-SOURCE
> Task :buildSrc:pluginDescriptors UP-TO-DATE
> Task :buildSrc:processResources UP-TO-DATE
> Task :buildSrc:classes UP-TO-DATE
> Task :buildSrc:jar UP-TO-DATE
> Task :libsignal-cli:compileJava

BUILD SUCCESSFUL in 1s
```

Note: the Gradle project for the `lib/` source tree is actually named `:libsignal-cli` (see `settings.gradle.kts`: `project(":libsignal-cli").projectDir = file("lib")`). Gradle's task-selector prefix matching resolved `:lib:compileJava` to `:libsignal-cli:compileJava`, which compiled successfully — confirming the lib module (including these changes) compiles cleanly on its own, without the command layer.

As expected/documented in the brief, the command layer (`SendStoryCommand`, `DbusManagerImpl`, `StubManager`) was intentionally left untouched and will fail to compile against the new 3-parameter `sendStory` signature until Tasks 2/3 update those call sites. This was not exercised here since only `:lib:compileJava` was run.

## Concerns

None. The implementation follows the brief's step-by-step spec verbatim, reuses the exact patterns already present in the "My Story" code paths (`sendStory` in ManagerImpl, `sendStoryMessage`/`getGroupSendEndorsements`/`GroupSendEndorsements` construction in SendHelper), and required no new imports in SendHelper since all needed types were already imported for the pre-existing sender-key group-message code path.
