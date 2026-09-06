package org.asamk.signal.manager.helper;

import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.actions.HandleAction;
import org.asamk.signal.manager.actions.RefreshPreKeysAction;
import org.asamk.signal.manager.actions.RenewSessionAction;
import org.asamk.signal.manager.actions.ResendMessageAction;
import org.asamk.signal.manager.actions.RetrieveDeviceNameAction;
import org.asamk.signal.manager.actions.RetrieveProfileAction;
import org.asamk.signal.manager.actions.SendGroupInfoAction;
import org.asamk.signal.manager.actions.SendGroupInfoRequestAction;
import org.asamk.signal.manager.actions.SendProfileKeyAction;
import org.asamk.signal.manager.actions.SendReceiptAction;
import org.asamk.signal.manager.actions.SendRetryMessageRequestAction;
import org.asamk.signal.manager.actions.SendSyncBlockedListAction;
import org.asamk.signal.manager.actions.SendSyncConfigurationAction;
import org.asamk.signal.manager.actions.SendSyncContactsAction;
import org.asamk.signal.manager.actions.SendSyncGroupsAction;
import org.asamk.signal.manager.actions.SendSyncKeysAction;
import org.asamk.signal.manager.actions.SyncStorageDataAction;
import org.asamk.signal.manager.actions.UpdateAccountAttributesAction;
import org.asamk.signal.manager.api.GroupId;
import org.asamk.signal.manager.api.GroupNotFoundException;
import org.asamk.signal.manager.api.MessageEnvelope;
import org.asamk.signal.manager.api.Pair;
import org.asamk.signal.manager.api.ReceiveConfig;
import org.asamk.signal.manager.api.StickerPackId;
import org.asamk.signal.manager.api.TrustLevel;
import org.asamk.signal.manager.api.UntrustedIdentityException;
import org.asamk.signal.manager.groups.GroupUtils;
import org.asamk.signal.manager.internal.SignalDependencies;
import org.asamk.signal.manager.jobs.RetrieveStickerPackJob;
import org.asamk.signal.manager.storage.SignalAccount;
import org.asamk.signal.manager.storage.groups.GroupInfoV1;
import org.asamk.signal.manager.storage.recipients.RecipientAddress;
import org.asamk.signal.manager.storage.recipients.RecipientId;
import org.asamk.signal.manager.storage.stickers.StickerPack;
import org.asamk.signal.manager.util.MimeUtils;
import org.signal.core.models.ServiceId;
import org.signal.core.models.ServiceId.ACI;
import org.signal.libsignal.metadata.ProtocolException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.metadata.ProtocolNoSessionException;
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException;
import org.signal.libsignal.metadata.SelfSendException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.DecryptionErrorMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.InvalidMessageStructureException;
import org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult;
import org.whispersystems.signalservice.api.messages.EnvelopeContentValidator;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupContext;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;
import org.whispersystems.signalservice.api.messages.SignalServicePniSignatureMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceStoryMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.signalservice.api.push.ServiceIdType;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.Envelope;
import org.whispersystems.signalservice.internal.push.UnsupportedDataMessageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class IncomingMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(IncomingMessageHandler.class);

    private final SignalAccount account;
    private final SignalDependencies dependencies;
    private final Context context;

    public IncomingMessageHandler(final Context context) {
        this.account = context.getAccount();
        this.dependencies = context.getDependencies();
        this.context = context;
    }

    public Pair<List<HandleAction>, Exception> handleRetryEnvelope(
            final SignalServiceEnvelope envelope,
            final ReceiveConfig receiveConfig,
            final Manager.ReceiveMessageHandler handler
    ) {
        final List<HandleAction> actions = new ArrayList<>();
        if (envelope.isPreKeySignalMessage()) {
            actions.add(RefreshPreKeysAction.create());
        }

        SignalServiceContent content = null;
        if (!envelope.isReceipt()) {
            account.getIdentityKeyStore().setRetryingDecryption(true);
            try {
                final var destination = getDestination(envelope).serviceId();
                final var cipherResult = dependencies.getCipher(destination == null
                                || destination.equals(account.getAci()) ? ServiceIdType.ACI : ServiceIdType.PNI)
                        .decrypt(envelope.getProto(), envelope.getServerDeliveredTimestamp());
                content = validate(envelope.getProto(), cipherResult, envelope.getServerDeliveredTimestamp());
                if (content == null) {
                    return new Pair<>(List.of(), null);
                }
            } catch (ProtocolUntrustedIdentityException e) {
                final var recipientId = account.getRecipientResolver().resolveRecipient(e.getSender());
                final var exception = new UntrustedIdentityException(account.getRecipientAddressResolver()
                        .resolveRecipientAddress(recipientId)
                        .toApiRecipientAddress(), e.getSenderDevice());
                return new Pair<>(List.of(), exception);
            } catch (Exception e) {
                return new Pair<>(List.of(), e);
            } finally {
                account.getIdentityKeyStore().setRetryingDecryption(false);
            }
        }
        actions.addAll(checkAndHandleMessage(envelope, content, receiveConfig, handler, null));
        return new Pair<>(actions, null);
    }

    public Pair<List<HandleAction>, Exception> handleEnvelope(
            final SignalServiceEnvelope envelope,
            final ReceiveConfig receiveConfig,
            final Manager.ReceiveMessageHandler handler
    ) {
        final var actions = new ArrayList<HandleAction>();
        if (envelope.isPreKeySignalMessage()) {
            actions.add(RefreshPreKeysAction.create());
        }
        SignalServiceContent content = null;
        Exception exception = null;
        if (envelope.getSourceServiceId() != null) {
            // Store uuid if we don't have it already
            // uuid in envelope is sent by server
            account.getRecipientResolver().resolveRecipient(envelope.getSourceServiceId());
        }
        if (!envelope.isReceipt()) {
            try {
                final var destination = getDestination(envelope).serviceId();

                if (destination == account.getPni() && envelope.getSourceServiceId() == null) {
                    throw new InvalidMessageException(
                            "Got a sealed sender message to our PNI? Invalid message, ignoring.");
                }

                if (envelope.getSourceServiceId() instanceof ServiceId.PNI
                        && envelope.getProto().type != Envelope.Type.SERVER_DELIVERY_RECEIPT) {
                    throw new InvalidMessageException("Got a message from a PNI that was not a SERVER_DELIVERY_RECEIPT.");
                }

                final var cipherResult = dependencies.getCipher(destination == null
                                || destination.equals(account.getAci()) ? ServiceIdType.ACI : ServiceIdType.PNI)
                        .decrypt(envelope.getProto(), envelope.getServerDeliveredTimestamp());
                content = validate(envelope.getProto(), cipherResult, envelope.getServerDeliveredTimestamp());
                if (content == null) {
                    return new Pair<>(List.of(), null);
                }
            } catch (ProtocolUntrustedIdentityException e) {
                final var recipientId = account.getRecipientResolver().resolveRecipient(e.getSender());
                actions.add(new RetrieveProfileAction(recipientId));
                exception = new UntrustedIdentityException(account.getRecipientAddressResolver()
                        .resolveRecipientAddress(recipientId)
                        .toApiRecipientAddress(), e.getSenderDevice());
            } catch (ProtocolInvalidKeyIdException | ProtocolInvalidKeyException | ProtocolNoSessionException |
                     ProtocolInvalidMessageException e) {
                logger.debug("Failed to decrypt incoming message", e);
                // Resolved once here and shared with the diagnostic below.
                // resolveRecipient is a get-or-create that opens an IMMEDIATE write
                // transaction on a cache miss, and a freshly inserted row is not cached,
                // so resolving separately inside the diagnostic would double the write
                // transactions in exactly the first-contact burst it exists to study.
                final var sender = account.getRecipientResolver().resolveRecipient(e.getSender());
                logDecryptFailureDiagnostic(envelope, e, sender);
                if (e instanceof ProtocolInvalidKeyIdException) {
                    actions.add(RefreshPreKeysAction.create());
                }
                if (context.getContactHelper().isContactBlocked(sender)) {
                    logger.debug("Received invalid message from blocked contact, ignoring.");
                } else {
                    var serviceId = ServiceId.parseOrNull(e.getSender());
                    ServiceId destination;
                    try {
                        destination = getDestination(envelope).serviceId();
                    } catch (InvalidMessageException ex) {
                        destination = null;
                    }
                    if (serviceId != null && destination != null) {
                        final var isSelf = sender.equals(account.getSelfRecipientId())
                                && e.getSenderDevice() == account.getDeviceId();
                        logger.debug("Received invalid message, queuing renew session action.");
                        actions.add(new RenewSessionAction(sender, serviceId, destination));
                        if (!isSelf) {
                            logger.debug("Received invalid message, requesting message resend.");
                            actions.add(new SendRetryMessageRequestAction(sender, e, envelope));
                        }
                    } else {
                        logger.debug("Received invalid message from invalid sender: {}", e.getSender());
                    }
                }
                exception = e;
            } catch (SelfSendException e) {
                logger.debug("Dropping unidentified message from self.");
                return new Pair<>(List.of(), null);
            } catch (Exception e) {
                logger.debug("Failed to handle incoming message", e);
                exception = e;
            }
        }

        actions.addAll(checkAndHandleMessage(envelope, content, receiveConfig, handler, exception));
        return new Pair<>(actions, exception);
    }

    // Diagnostic: greppable, structured capture on any decrypt failure. Observational
    // only; never throws. Grep logs for "SIGNAL-CLI DECRYPT-FAIL".
    //
    // Sealed-sender blind spot this works around: envelope.isPreKeySignalMessage()
    // reads the OUTER envelope type, which for a sealed-sender message is always
    // UNIDENTIFIED_SENDER. It therefore reports false for every sealed message no
    // matter what is inside, and the inner prekey IDs never appear. Since sealed
    // sender is now the common case, that blind spot covered nearly all traffic:
    // a month of captured failures showed prekeyMsg=false and preKey=[n/a] on every
    // single line, which reads as "no PreKey failures happen" when it actually means
    // "this probe cannot see them".
    //
    // The exception carries the ALREADY-UNWRAPPED UnidentifiedSenderMessageContent,
    // so innerType/preKey below describe the message that really failed, with no
    // second decryption attempt. innerType is the field to trust; prekeyMsg is kept
    // only so old and new lines stay comparable.
    //
    // The catch is on Throwable, not Exception, and that is load-bearing rather than
    // defensive habit. Several calls below surface an Error, not an Exception:
    // GroupId.unknownVersion throws AssertionError unless the id is exactly 16 or 32
    // bytes, and that array is an opaque protobuf field chosen by the REMOTE SENDER;
    // libsignal's FilterExceptions turns an unexpected native failure into an
    // AssertionError too. An Error escaping here is not a lost log line — the receive
    // loop catches only Exception, the daemon thread lambda catches nothing, and its
    // `receiveThread = null` reset sits after the call and so never runs, leaving
    // startReceiveThreadIfRequired convinced the thread is still alive. The account
    // would stop receiving until the process restarted, on a message any sender could
    // craft. A diagnostic must never be able to do that.
    private void logDecryptFailureDiagnostic(
            final SignalServiceEnvelope envelope, final ProtocolException e, final RecipientId senderId
    ) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        try {
            final var aci = account.getAci();
            final var pni = account.getPni();
            Object destination;
            try {
                destination = getDestination(envelope).serviceId();
            } catch (Exception ignored) {
                destination = null;
            }
            final String destType = destination == null ? "unknown"
                    : destination.equals(aci) ? "ACI"
                    : destination.equals(pni) ? "PNI" : "other";

            // Inner view: prefer the unwrapped sealed-sender content, fall back to the
            // envelope's own type for a plain (non-sealed) message. The inner ciphertext
            // is fetched ONLY for a PreKey message — getContent() marshals the whole
            // payload across JNI and every other type would discard it unread.
            final String innerType;
            byte[] innerContent = null;
            final var usmc = e.getUnidentifiedSenderMessageContent();
            if (usmc.isPresent()) {
                final int type = usmc.get().getType();
                innerType = ciphertextTypeName(type);
                if (type == CiphertextMessage.PREKEY_TYPE) {
                    innerContent = usmc.get().getContent();
                }
            } else {
                innerType = envelopeTypeName(envelope.getType());
                if (envelope.isPreKeySignalMessage() && envelope.hasContent()) {
                    // getContent() is null-safe; the raw proto content field is not.
                    innerContent = envelope.getContent();
                }
            }

            String preKey = "n/a";
            if (innerContent != null) {
                try {
                    final var pkm = new PreKeySignalMessage(innerContent);
                    preKey = "regId=" + pkm.getRegistrationId()
                            + ",signedPreKeyId=" + pkm.getSignedPreKeyId()
                            + ",preKeyId=" + pkm.getPreKeyId().map(String::valueOf).orElse("none");
                } catch (Exception parseEx) {
                    preKey = "parse-failed(" + parseEx.getClass().getSimpleName() + ")";
                }
            }

            // The sender is the caller's already-resolved RecipientId — an internal
            // numeric id, not a raw service id — which joins against the sibling
            // "Sending retry receipt ... to RecipientId[id=N]" and "Marking
            // RecipientId[id=N] ..." lines. Note this is a reduction in exposure, not
            // an absence of it: groupId below is a stable group identifier, and
            // exc={message} can carry ids libsignal chose to put in its text. These
            // lines are shipped off-box, so treat the whole line as identifying.
            final String sender = senderId == null ? "unknown" : String.valueOf(senderId.id());

            String senderTrust = "unknown";
            final var senderServiceId = ServiceId.parseOrNull(e.getSender());
            if (senderServiceId != null) {
                try {
                    // An untrusted identity is why a retry receipt / RenewSessionAction
                    // cannot be delivered, so the failure repeats indefinitely instead of
                    // self-healing. Capturing it makes that diagnosable from one line.
                    // A read-only lookup: isTrustedIdentity would WRITE, this must not.
                    final var identity = account.getIdentityKeyStore().getIdentityInfo(senderServiceId);
                    // "none" also covers a stored key that failed to parse — the store
                    // returns null for both, and they are not distinguishable here.
                    senderTrust = identity == null ? "none" : String.valueOf(identity.getTrustLevel());
                } catch (Exception ignored) {
                    // keep "unknown"
                }
            }

            // GroupId.unknownVersion throws AssertionError on any length but 16 or 32,
            // and these bytes are chosen by the remote sender — so check before calling
            // rather than relying on the catch below.
            final String groupId = e.getGroupId()
                    .map(id -> id.length == 16 || id.length == 32
                            ? GroupId.unknownVersion(id).toBase64()
                            : "invalid-length-" + id.length)
                    .orElse("n/a");

            final long ts = envelope.getTimestamp();
            final long deliveredTs = envelope.getServerDeliveredTimestamp();
            logger.warn("SIGNAL-CLI DECRYPT-FAIL dest={} sealed={} prekeyMsg={} innerType={} exc={}:{} "
                            + "sender={} senderTrust={} senderDevice={} groupId={} contentHint={} "
                            + "msgTs={} deliveredTs={} lagMs={} preKey=[{}]",
                    destType,
                    envelope.isUnidentifiedSender(),
                    envelope.isPreKeySignalMessage(),
                    innerType,
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    sender,
                    senderTrust,
                    e.getSenderDevice(),
                    groupId,
                    e.getContentHint(),
                    ts,
                    deliveredTs,
                    deliveredTs - ts,
                    preKey);
        } catch (Throwable diagEx) {
            // Throwable, not Exception — see the note on this method. Swallowing an
            // Error here costs one log line; letting it out costs the receive thread.
            logger.debug("DECRYPT-FAIL diagnostic itself failed", diagEx);
        }
    }

    private static String ciphertextTypeName(final int type) {
        return switch (type) {
            case CiphertextMessage.WHISPER_TYPE -> "WHISPER";
            case CiphertextMessage.PREKEY_TYPE -> "PREKEY";
            case CiphertextMessage.SENDERKEY_TYPE -> "SENDERKEY";
            case CiphertextMessage.PLAINTEXT_CONTENT_TYPE -> "PLAINTEXT";
            default -> "type" + type;
        };
    }

    // Normalised into the SAME vocabulary as ciphertextTypeName, so one query matches a
    // given inner type whether or not the message was sealed. The envelope enum's own
    // names would not: "PREKEY_MESSAGE" has "PREKEY" as a strict prefix, so a loose
    // query silently over-matches and an exact one silently under-matches — the same
    // shape of miscount this change exists to remove.
    private static String envelopeTypeName(final Integer envelopeType) {
        if (envelopeType == null) {
            return "unknown";
        }
        final var type = Envelope.Type.fromValue(envelopeType);
        if (type == null) {
            return "type" + envelopeType;
        }
        return switch (type) {
            case PREKEY_MESSAGE -> ciphertextTypeName(CiphertextMessage.PREKEY_TYPE);
            case PLAINTEXT_CONTENT -> ciphertextTypeName(CiphertextMessage.PLAINTEXT_CONTENT_TYPE);
            // A sealed envelope whose unwrapped content the exception did not carry.
            // Named distinctly rather than folded into SENDERKEY the way the
            // retry-receipt path does, because here it means "inner type unavailable",
            // which is a different claim from "the inner message was a sender-key one".
            case UNIDENTIFIED_SENDER -> "SEALED_NO_CONTENT";
            default -> ciphertextTypeName(CiphertextMessage.WHISPER_TYPE);
        };
    }

    private SignalServiceContent validate(
            Envelope envelope,
            SignalServiceCipherResult cipherResult,
            long serverDeliveredTimestamp
    ) throws ProtocolInvalidKeyException, ProtocolInvalidMessageException, UnsupportedDataMessageException, InvalidMessageStructureException {
        final var content = cipherResult.getContent();
        final var envelopeMetadata = cipherResult.getMetadata();
        final var validationResult = EnvelopeContentValidator.INSTANCE.validate(envelope,
                content,
                account.getAci(),
                cipherResult.getMetadata().getCiphertextMessageType());

        if (validationResult instanceof EnvelopeContentValidator.Result.Invalid v) {
            logger.warn("Invalid content! {}", v.getReason(), v.getThrowable());
            return null;
        }

        if (validationResult instanceof EnvelopeContentValidator.Result.UnsupportedDataMessage v) {
            logger.warn("Unsupported DataMessage! Our version: {}, their version: {}",
                    v.getOurVersion(),
                    v.getTheirVersion());
            return null;
        }

        return SignalServiceContent.Companion.createFrom(account.getNumber(),
                envelope,
                envelopeMetadata,
                content,
                serverDeliveredTimestamp);
    }

    private List<HandleAction> checkAndHandleMessage(
            final SignalServiceEnvelope envelope,
            final SignalServiceContent content,
            final ReceiveConfig receiveConfig,
            final Manager.ReceiveMessageHandler handler,
            final Exception exception
    ) {
        if (content != null) {
            // Store uuid if we don't have it already
            // address/uuid is validated by unidentified sender certificate

            boolean handledPniSignature = false;
            if (content.getPniSignatureMessage().isPresent()) {
                final var message = content.getPniSignatureMessage().get();
                final var senderAddress = getSenderAddress(envelope, content);
                if (senderAddress != null) {
                    handledPniSignature = handlePniSignatureMessage(message, senderAddress);
                }
            }
            if (!handledPniSignature) {
                account.getRecipientTrustedResolver().resolveRecipientTrusted(content.getSender());
            }
        }
        if (envelope.isReceipt()) {
            final var senderDeviceAddress = getSender(envelope, content);
            final var sender = senderDeviceAddress.serviceId();
            final var senderDeviceId = senderDeviceAddress.deviceId();
            account.getMessageSendLogStore().deleteEntryForRecipient(envelope.getTimestamp(), sender, senderDeviceId);
        }

        var notAllowedToSendToGroup = isNotAllowedToSendToGroup(envelope, content);
        final var groupContext = getGroupContext(content);
        if (groupContext != null && groupContext.getGroupV2().isPresent()) {
            handleGroupV2Context(groupContext.getGroupV2().get(), receiveConfig.ignoreAvatars());
        }
        // Check again in case the user just joined the group
        notAllowedToSendToGroup = notAllowedToSendToGroup && isNotAllowedToSendToGroup(envelope, content);

        if (isMessageBlocked(envelope, content)) {
            logger.info("Ignoring a message from blocked user/group: {}", envelope.getTimestamp());
            return List.of();
        } else if (notAllowedToSendToGroup) {
            final var senderAddress = getSenderAddress(envelope, content);
            logger.info("Ignoring a group message from an unauthorized sender (no member or admin): {} {}",
                    senderAddress == null ? null : senderAddress.getIdentifier(),
                    envelope.getTimestamp());
            return List.of();
        } else {
            List<HandleAction> actions;
            Map<String, String> longTexts;
            if (content != null) {
                final var results = handleMessage(envelope, content, receiveConfig);
                actions = results.first();
                longTexts = results.second();
            } else {
                actions = List.of();
                longTexts = Map.of();
            }
            handler.handleMessage(MessageEnvelope.from(envelope,
                    content,
                    longTexts,
                    account.getRecipientResolver(),
                    account.getRecipientAddressResolver(),
                    context.getAttachmentHelper()::getAttachmentFile,
                    exception), exception);
            return actions;
        }
    }

    public Pair<List<HandleAction>, Map<String, String>> handleMessage(
            SignalServiceEnvelope envelope,
            SignalServiceContent content,
            ReceiveConfig receiveConfig
    ) {
        final var actions = new ArrayList<HandleAction>();
        final var longTexts = new HashMap<String, String>();
        final var senderDeviceAddress = getSender(envelope, content);
        final var sender = senderDeviceAddress.recipientId();
        final var senderServiceId = senderDeviceAddress.serviceId();
        final var senderDeviceId = senderDeviceAddress.deviceId();
        final DeviceAddress destination;
        try {
            destination = getDestination(envelope);
        } catch (InvalidMessageException e) {
            throw new AssertionError(e);
        }

        if (account.getPni().equals(destination.serviceId)) {
            account.getRecipientStore().markNeedsPniSignature(sender, true);
        } else if (account.getAci().equals(destination.serviceId)) {
            account.getRecipientStore().markNeedsPniSignature(sender, false);
        }

        if (content.getReceiptMessage().isPresent()) {
            final var message = content.getReceiptMessage().get();
            if (message.isDeliveryReceipt()) {
                account.getMessageSendLogStore()
                        .deleteEntriesForRecipient(message.getTimestamps(), senderServiceId, senderDeviceId);
            }
        }

        if (content.getSenderKeyDistributionMessage().isPresent()) {
            final var message = content.getSenderKeyDistributionMessage().get();
            final var protocolAddress = senderServiceId.toProtocolAddress(senderDeviceId);
            logger.debug("Received a sender key distribution message for distributionId {} from {}",
                    message.getDistributionId(),
                    protocolAddress);
            new SignalGroupSessionBuilder(dependencies.getSessionLock(),
                    new GroupSessionBuilder(account.getSenderKeyStore())).process(protocolAddress, message);
        }

        if (content.getDecryptionErrorMessage().isPresent()) {
            var message = content.getDecryptionErrorMessage().get();
            logger.debug("Received a decryption error message from {}.{} (resend request for {})",
                    sender,
                    senderDeviceId,
                    message.getTimestamp());
            if (message.getDeviceId() == account.getDeviceId()) {
                handleDecryptionErrorMessage(actions,
                        sender,
                        senderServiceId,
                        senderDeviceId,
                        message,
                        destination.serviceId());
            } else {
                logger.debug("Request is for another one of our devices");
            }
        }

        if (content.getDataMessage().isPresent() || content.getEditMessage().isPresent()) {
            var message = content.getDataMessage().isPresent()
                    ? content.getDataMessage().get()
                    : content.getEditMessage().get().getDataMessage();

            if (content.isNeedsReceipt()) {
                actions.add(new SendReceiptAction(sender,
                        SignalServiceReceiptMessage.Type.DELIVERY,
                        message.getTimestamp()));
            } else {
                // Message wasn't sent as unidentified sender message
                final var contact = context.getAccount().getContactStore().getContact(sender);
                if (account.isPrimaryDevice()
                        && contact != null
                        && !contact.isBlocked()
                        && contact.isProfileSharingEnabled()) {
                    actions.add(UpdateAccountAttributesAction.create());
                    actions.add(new SendProfileKeyAction(sender));
                }
            }
            if (receiveConfig.sendReadReceipts()) {
                actions.add(new SendReceiptAction(sender,
                        SignalServiceReceiptMessage.Type.READ,
                        message.getTimestamp()));
            }

            final var dataResults = handleSignalServiceDataMessage(message,
                    false,
                    senderDeviceAddress,
                    destination,
                    receiveConfig);
            actions.addAll(dataResults.first());
            longTexts.putAll(dataResults.second());
        }

        if (content.getStoryMessage().isPresent()) {
            final var message = content.getStoryMessage().get();
            actions.addAll(handleSignalServiceStoryMessage(message, sender, receiveConfig));
        }

        if (content.getSyncMessage().isPresent()) {
            var syncMessage = content.getSyncMessage().get();
            final var syncResults = handleSyncMessage(envelope, syncMessage, senderDeviceAddress, receiveConfig);
            actions.addAll(syncResults.first());
            longTexts.putAll(syncResults.second());
        }

        if (content.getCallMessage().isPresent()) {
            handleCallMessage(content.getCallMessage().get(), sender, senderDeviceId);
        }

        return new Pair<>(actions, longTexts);
    }

    private void handleCallMessage(
            final SignalServiceCallMessage callMessage,
            final RecipientId sender,
            final int deviceId
    ) {
        var callManager = context.getCallManager();
        if (callMessage.getDestinationDeviceId().isPresent()
                && callMessage.getDestinationDeviceId().get() != account.getDeviceId()) {
            return;
        }

        callMessage.getOfferMessage().ifPresent(offer -> {
            var type = offer.getType()
                    == org.whispersystems.signalservice.api.messages.calls.OfferMessage.Type.VIDEO_CALL
                    ? org.asamk.signal.manager.api.MessageEnvelope.Call.Offer.Type.VIDEO_CALL
                    : org.asamk.signal.manager.api.MessageEnvelope.Call.Offer.Type.AUDIO_CALL;
            callManager.handleIncomingOffer(sender, deviceId, offer.getId(), type, offer.getOpaque());
        });

        callMessage.getAnswerMessage()
                .ifPresent(answer -> callManager.handleIncomingAnswer(answer.getId(), deviceId, answer.getOpaque()));

        callMessage.getIceUpdateMessages().ifPresent(iceUpdates -> {
            for (var ice : iceUpdates) {
                callManager.handleIncomingIceCandidate(ice.getId(), ice.getOpaque(), deviceId);
            }
        });

        callMessage.getHangupMessage().ifPresent(hangup -> {
            // Only NORMAL hangups actually end the call. ACCEPTED/DECLINED/BUSY
            // are multi-device notifications irrelevant for single-device signal-cli.
            var hangupType = hangup.getType();
            if (hangupType == org.whispersystems.signalservice.api.messages.calls.HangupMessage.Type.NORMAL
                    || hangupType == null) {
                callManager.handleIncomingHangup(hangup.getId());
            }
        });

        callMessage.getBusyMessage().ifPresent(busy -> callManager.handleIncomingBusy(busy.getId()));
    }

    private boolean handlePniSignatureMessage(
            final SignalServicePniSignatureMessage message,
            final SignalServiceAddress senderAddress
    ) {
        final var aci = senderAddress.getServiceId();
        final var aciIdentity = account.getIdentityKeyStore().getIdentityInfo(aci);
        final var pni = message.getPni();
        final var pniIdentity = account.getIdentityKeyStore().getIdentityInfo(pni);

        if (aciIdentity == null || pniIdentity == null || aci.equals(pni)) {
            return false;
        }

        final var verified = pniIdentity.getIdentityKey()
                .verifyAlternateIdentity(aciIdentity.getIdentityKey(), message.getSignature());

        if (!verified) {
            logger.debug("Invalid PNI signature of ACI {} with PNI {}", aci, pni);
            return false;
        }

        logger.debug("Verified association of ACI {} with PNI {}", aci, pni);
        final var recipientId = account.getRecipientTrustedResolver()
                .resolveRecipientTrusted(Optional.of(ACI.from(aci.getRawUuid())),
                        Optional.of(pni),
                        senderAddress.getNumber());
        account.getRecipientStore().markPniSignatureVerified(recipientId);
        return true;
    }

    private void handleDecryptionErrorMessage(
            final List<HandleAction> actions,
            final RecipientId sender,
            final ServiceId senderServiceId,
            final int senderDeviceId,
            final DecryptionErrorMessage message,
            final ServiceId destination
    ) {
        final var logEntries = account.getMessageSendLogStore()
                .findMessages(senderServiceId,
                        senderDeviceId,
                        message.getTimestamp(),
                        message.getRatchetKey().isEmpty());

        for (final var logEntry : logEntries) {
            actions.add(new ResendMessageAction(sender, message.getTimestamp(), logEntry));
        }

        if (message.getRatchetKey().isPresent()) {
            final var sessionStore = account.getAccountData(destination).getSessionStore();
            if (sessionStore.isCurrentRatchetKey(senderServiceId, senderDeviceId, message.getRatchetKey().get())) {
                if (logEntries.isEmpty()) {
                    logger.debug("Renewing the session with sender");
                    actions.add(new RenewSessionAction(sender, senderServiceId, destination));
                } else {
                    logger.trace("Archiving the session with sender, a resend message has already been queued");
                    sessionStore.archiveSessions(senderServiceId);
                }
            }
            return;
        }

        var found = false;
        for (final var logEntry : logEntries) {
            if (logEntry.groupId().isEmpty()) {
                continue;
            }
            final var group = account.getGroupStore().getGroup(logEntry.groupId().get());
            if (group == null) {
                continue;
            }
            found = true;
            logger.trace("Deleting shared sender key with {} ({}): {}",
                    sender,
                    senderDeviceId,
                    group.getDistributionId());
            account.getSenderKeyStore().deleteSharedWith(senderServiceId, senderDeviceId, group.getDistributionId());
        }
        if (!found) {
            logger.debug("Reset all shared sender keys with this recipient, no related message found in send log");
            account.getSenderKeyStore().deleteSharedWith(senderServiceId);
        }
    }

    private Pair<List<HandleAction>, Map<String, String>> handleSyncMessage(
            final SignalServiceEnvelope envelope,
            final SignalServiceSyncMessage syncMessage,
            final DeviceAddress sender,
            final ReceiveConfig receiveConfig
    ) {
        final var actions = new ArrayList<HandleAction>();
        final var longTexts = new HashMap<String, String>();
        account.setMultiDevice(true);
        if (syncMessage.getSent().isPresent()) {
            var message = syncMessage.getSent().get();
            final var destination = message.getDestination().orElse(null);
            if (message.getDataMessage().isPresent()) {
                final var dataResults = handleSignalServiceDataMessage(message.getDataMessage().get(),
                        true,
                        sender,
                        destination == null
                                ? null
                                : new DeviceAddress(account.getRecipientResolver().resolveRecipient(destination),
                                        destination.getServiceId(),
                                        0),
                        receiveConfig);
                actions.addAll(dataResults.first());
                longTexts.putAll(dataResults.second());
            }
            if (message.getStoryMessage().isPresent()) {
                actions.addAll(handleSignalServiceStoryMessage(message.getStoryMessage().get(),
                        sender.recipientId(),
                        receiveConfig));
            }
        }
        if (syncMessage.getRequest().isPresent() && account.isPrimaryDevice()) {
            var rm = syncMessage.getRequest().get();
            if (rm.isContactsRequest()) {
                actions.add(SendSyncContactsAction.create());
            }
            if (rm.isGroupsRequest()) {
                actions.add(SendSyncGroupsAction.create());
            }
            if (rm.isBlockedListRequest()) {
                actions.add(SendSyncBlockedListAction.create());
            }
            if (rm.isKeysRequest()) {
                actions.add(SendSyncKeysAction.create());
            }
            if (rm.isConfigurationRequest()) {
                actions.add(SendSyncConfigurationAction.create());
            }
            actions.add(SyncStorageDataAction.create());
        }
        if (syncMessage.getGroups().isPresent()) {
            try {
                final var groupsMessage = syncMessage.getGroups().get();
                context.getAttachmentHelper()
                        .retrieveAttachment(groupsMessage,
                                input -> context.getSyncHelper()
                                        .handleSyncDeviceGroups(input, receiveConfig.ignoreAvatars()));
            } catch (Exception e) {
                logger.warn("Failed to handle received sync groups, ignoring: {}", e.getMessage());
            }
        }
        if (syncMessage.getBlockedList().isPresent()) {
            final var blockedListMessage = syncMessage.getBlockedList().get();
            for (var individual : blockedListMessage.individuals) {
                final var address = new RecipientAddress(individual.getAci(), individual.getE164());
                final var recipientId = account.getRecipientResolver().resolveRecipient(address);
                context.getContactHelper().setContactBlocked(recipientId, true);
            }
            for (var groupId : blockedListMessage.groupIds.stream()
                    .map(GroupId::unknownVersion)
                    .collect(Collectors.toSet())) {
                try {
                    context.getGroupHelper().setGroupBlocked(groupId, true);
                } catch (GroupNotFoundException e) {
                    logger.warn("BlockedListMessage contained groupID that was not found in GroupStore: {}",
                            groupId.toBase64());
                }
            }
        }
        if (syncMessage.getContacts().isPresent()) {
            try {
                final var contactsMessage = syncMessage.getContacts().get();
                context.getAttachmentHelper()
                        .retrieveAttachment(contactsMessage.getContactsStream(),
                                input -> context.getSyncHelper()
                                        .handleSyncDeviceContacts(input, receiveConfig.ignoreAvatars()));
            } catch (Exception e) {
                logger.warn("Failed to handle received sync contacts, ignoring: {}", e.getMessage());
            }
        }
        if (syncMessage.getVerified().isPresent()) {
            final var verifiedMessage = syncMessage.getVerified().get();
            account.getIdentityKeyStore()
                    .setIdentityTrustLevel(verifiedMessage.getDestination().getServiceId(),
                            verifiedMessage.getIdentityKey(),
                            TrustLevel.fromVerifiedState(verifiedMessage.getVerified()));
        }
        if (syncMessage.getStickerPackOperations().isPresent()) {
            final var stickerPackOperationMessages = syncMessage.getStickerPackOperations().get();
            for (var m : stickerPackOperationMessages) {
                if (m.getPackId().isEmpty()) {
                    continue;
                }
                final var stickerPackId = StickerPackId.deserialize(m.getPackId().get());
                final var stickerPackKey = m.getPackKey().orElse(null);
                final var installed = m.getType().isEmpty()
                        || m.getType().get() == StickerPackOperationMessage.Type.INSTALL;

                final var sticker = context.getStickerHelper()
                        .addOrUpdateStickerPack(stickerPackId, stickerPackKey, installed);

                if (sticker != null && installed && !receiveConfig.ignoreStickers()) {
                    context.getJobExecutor().enqueueJob(new RetrieveStickerPackJob(stickerPackId, sticker.packKey()));
                }
            }
        }
        if (syncMessage.getFetchType().isPresent()) {
            switch (syncMessage.getFetchType().get()) {
                case LOCAL_PROFILE -> actions.add(new RetrieveProfileAction(account.getSelfRecipientId()));
                case STORAGE_MANIFEST -> actions.add(SyncStorageDataAction.create());
            }
        }
        if (syncMessage.getKeys().isPresent()) {
            final var keysMessage = syncMessage.getKeys().get();
            if (keysMessage.getAccountEntropyPool() != null) {
                final var aep = keysMessage.getAccountEntropyPool();
                account.setAccountEntropyPool(aep);
                actions.add(SyncStorageDataAction.create());
            } else if (keysMessage.getStorageService() != null) {
                final var storageKey = keysMessage.getStorageService();
                account.setStorageKey(storageKey);
                actions.add(SyncStorageDataAction.create());
            }
            if (keysMessage.getMediaRootBackupKey() != null) {
                final var mrb = keysMessage.getMediaRootBackupKey();
                account.setMediaRootBackupKey(mrb);
                actions.add(SyncStorageDataAction.create());
            }
        }
        if (syncMessage.getConfiguration().isPresent()) {
            final var configurationMessage = syncMessage.getConfiguration().get();
            final var configurationStore = account.getConfigurationStore();
            if (configurationMessage.getReadReceipts().isPresent()) {
                configurationStore.setReadReceipts(configurationMessage.getReadReceipts().get());
            }
            if (configurationMessage.getLinkPreviews().isPresent()) {
                configurationStore.setLinkPreviews(configurationMessage.getLinkPreviews().get());
            }
            if (configurationMessage.getTypingIndicators().isPresent()) {
                configurationStore.setTypingIndicators(configurationMessage.getTypingIndicators().get());
            }
            if (configurationMessage.getUnidentifiedDeliveryIndicators().isPresent()) {
                configurationStore.setUnidentifiedDeliveryIndicators(configurationMessage.getUnidentifiedDeliveryIndicators()
                        .get());
            }
        }
        if (syncMessage.getPniChangeNumber().isPresent()) {
            final var pniChangeNumber = syncMessage.getPniChangeNumber().get();
            if (account.isPrimaryDevice()) {
                logger.warn("Received PNI change number sync message on primary device, ignoring.");
            } else if (sender.deviceId() != SignalServiceAddress.DEFAULT_DEVICE_ID) {
                logger.warn("Received PNI change number sync message from non-primary device {}, ignoring.",
                        sender.deviceId());
            } else {
                final var envelopeServerTimestamp = envelope.getServerDeliveredTimestamp();
                final var lastAppliedServerTimestamp = account.getLastAppliedPniChangeServerTimestamp();
                if (envelopeServerTimestamp <= lastAppliedServerTimestamp) {
                    logger.warn(
                            "PNI change number sync server timestamp ({}) is not newer than last applied ({}), treating as replay.",
                            envelopeServerTimestamp,
                            lastAppliedServerTimestamp);
                } else {
                    final var updatedPniString = envelope.getUpdatedPni();
                    if (updatedPniString != null && !updatedPniString.isEmpty()) {
                        final var updatedPni = ServiceId.PNI.parseOrThrow(updatedPniString);
                        final var applied = context.getAccountHelper()
                                .handlePniChangeNumberMessage(pniChangeNumber, updatedPni, true);
                        if (applied) {
                            account.setLastAppliedPniChangeServerTimestamp(envelopeServerTimestamp);
                        }
                    }
                }
            }
        }
        if (syncMessage.getDeviceNameChange().isPresent()) {
            final var deviceNameChange = syncMessage.getDeviceNameChange().get();
            if (deviceNameChange.deviceId != null && deviceNameChange.deviceId == account.getDeviceId()) {
                actions.add(RetrieveDeviceNameAction.create());
            }
        }
        return new Pair<>(actions, longTexts);
    }

    private SignalServiceGroupContext getGroupContext(SignalServiceContent content) {
        if (content == null) {
            return null;
        }

        if (content.getDataMessage().isPresent()) {
            var message = content.getDataMessage().get();
            if (message.getGroupContext().isPresent()) {
                return message.getGroupContext().get();
            }
        }

        if (content.getStoryMessage().isPresent()) {
            var message = content.getStoryMessage().get();
            if (message.getGroupContext().isPresent()) {
                try {
                    return SignalServiceGroupContext.create(null, message.getGroupContext().get());
                } catch (InvalidMessageException e) {
                    throw new AssertionError(e);
                }
            }
        }

        return null;
    }

    private boolean isMessageBlocked(SignalServiceEnvelope envelope, SignalServiceContent content) {
        SignalServiceAddress source = getSenderAddress(envelope, content);
        if (source == null) {
            return false;
        }
        final var recipientId = account.getRecipientResolver().resolveRecipient(source);
        if (context.getContactHelper().isContactBlocked(recipientId)) {
            return true;
        }

        final var groupContext = getGroupContext(content);
        if (groupContext != null) {
            var groupId = GroupUtils.getGroupId(groupContext);
            return context.getGroupHelper().isGroupBlocked(groupId);
        }

        return false;
    }

    private boolean isNotAllowedToSendToGroup(SignalServiceEnvelope envelope, SignalServiceContent content) {
        SignalServiceAddress source = getSenderAddress(envelope, content);
        if (source == null) {
            return false;
        }

        final var groupContext = getGroupContext(content);
        if (groupContext == null) {
            return false;
        }

        if (groupContext.getGroupV1().isPresent()) {
            var groupInfo = groupContext.getGroupV1().get();
            if (groupInfo.getType() == SignalServiceGroup.Type.QUIT) {
                return false;
            }
        }

        final var message = content.getDataMessage().orElse(null);

        final var recipientId = account.getRecipientResolver().resolveRecipient(source);

        final var groupId = GroupUtils.getGroupId(groupContext);
        final var group = context.getGroupHelper().getGroup(groupId);

        if (message != null && message.getAdminDelete().isPresent() && (group == null || !group.isAdmin(recipientId))) {
            return true;
        }

        if (group == null) {
            return false;
        }

        if (!group.isMember(recipientId) && !(
                group.isPendingMember(recipientId) && message != null && message.isGroupV2Update()
        )) {
            return true;
        }

        if (group.isAnnouncementGroup() && !group.isAdmin(recipientId)) {
            return message == null
                    || message.getBody().isPresent()
                    || message.getAttachments().isPresent()
                    || message.getQuote().isPresent()
                    || message.getPreviews().isPresent()
                    || message.getMentions().isPresent()
                    || message.getSticker().isPresent();
        }
        return false;
    }

    private Pair<List<HandleAction>, Map<String, String>> handleSignalServiceDataMessage(
            SignalServiceDataMessage message,
            boolean isSync,
            DeviceAddress source,
            DeviceAddress destination,
            ReceiveConfig receiveConfig
    ) {
        final var longTexts = new HashMap<String, String>();
        var actions = new ArrayList<HandleAction>();
        if (message.getGroupContext().isPresent()) {
            final var groupContext = message.getGroupContext().get();
            if (groupContext.getGroupV1().isPresent()) {
                var groupInfo = groupContext.getGroupV1().get();
                var groupId = GroupId.v1(groupInfo.getGroupId());
                var group = context.getGroupHelper().getGroup(groupId);
                if (group == null || group instanceof GroupInfoV1) {
                    var groupV1 = (GroupInfoV1) group;
                    switch (groupInfo.getType()) {
                        case UPDATE -> {
                            if (groupV1 == null) {
                                groupV1 = new GroupInfoV1(groupId);
                            }

                            if (groupInfo.getAvatar().isPresent() && !receiveConfig.ignoreAvatars()) {
                                var avatar = groupInfo.getAvatar().get();
                                context.getGroupHelper().downloadGroupAvatar(groupV1.getGroupId(), avatar);
                            }

                            if (groupInfo.getName().isPresent()) {
                                groupV1.name = groupInfo.getName().get();
                            }

                            if (groupInfo.getMembers().isPresent()) {
                                final var recipientResolver = account.getRecipientResolver();
                                groupV1.addMembers(groupInfo.getMembers()
                                        .get()
                                        .stream()
                                        .map(recipientResolver::resolveRecipient)
                                        .collect(Collectors.toSet()));
                            }

                            account.getGroupStore().updateGroup(groupV1);
                        }
                        case DELIVER -> {
                            if (groupV1 == null && !isSync) {
                                actions.add(new SendGroupInfoRequestAction(source.recipientId(), groupId));
                            }
                        }
                        case QUIT -> {
                            if (groupV1 != null) {
                                groupV1.removeMember(source.recipientId());
                                account.getGroupStore().updateGroup(groupV1);
                            }
                        }
                        case REQUEST_INFO -> {
                            if (groupV1 != null && !isSync) {
                                actions.add(new SendGroupInfoAction(source.recipientId(), groupV1.getGroupId()));
                            }
                        }
                    }
                } else {
                    // Received a group v1 message for a v2 group
                }
            }
            if (groupContext.getGroupV2().isPresent()) {
                handleGroupV2Context(groupContext.getGroupV2().get(), receiveConfig.ignoreAvatars());
            }
        }

        final var selfAddress = isSync ? source : destination;
        final var conversationPartnerAddress = isSync ? destination : source;
        if (message.isExpirationUpdate() || message.getBody().isPresent()) {
            if (message.getGroupContext().isPresent()) {
                final var groupContext = message.getGroupContext().get();
                if (groupContext.getGroupV1().isPresent()) {
                    var groupInfo = groupContext.getGroupV1().get();
                    var group = account.getGroupStore().getOrCreateGroupV1(GroupId.v1(groupInfo.getGroupId()));
                    if (group != null) {
                        if (group.messageExpirationTime != message.getExpiresInSeconds()) {
                            group.messageExpirationTime = message.getExpiresInSeconds();
                            account.getGroupStore().updateGroup(group);
                        }
                    }
                } else if (groupContext.getGroupV2().isPresent()) {
                    // disappearing message timer already stored in the DecryptedGroup
                }
            } else if (conversationPartnerAddress != null) {
                context.getContactHelper()
                        .setExpirationTimer(conversationPartnerAddress.recipientId(),
                                message.getExpiresInSeconds(),
                                message.getExpireTimerVersion());
            }
        }
        if (!receiveConfig.ignoreAttachments()) {
            if (message.getAttachments().isPresent()) {
                for (var attachment : message.getAttachments().get()) {
                    context.getAttachmentHelper().downloadAttachment(attachment);
                    if (attachment.isPointer()) {
                        final var file = context.getAttachmentHelper().getAttachmentFile(attachment.asPointer());
                        if (MimeUtils.LONG_TEXT.equals(attachment.getContentType()) && attachment.isPointer()) {
                            try {
                                final var longText = Files.readString(file.toPath());
                                longTexts.put(attachment.asPointer().getRemoteId().toString(), longText);
                            } catch (IOException e) {
                                logger.warn("Failed to read long text attachment, ignoring", e);
                            }
                        }
                    }
                }
            }
            if (message.getSharedContacts().isPresent()) {
                for (var contact : message.getSharedContacts().get()) {
                    if (contact.getAvatar().isPresent()) {
                        context.getAttachmentHelper().downloadAttachment(contact.getAvatar().get().getAttachment());
                    }
                }
            }
            if (message.getPreviews().isPresent()) {
                final var previews = message.getPreviews().get();
                for (var preview : previews) {
                    if (preview.getImage().isPresent()) {
                        context.getAttachmentHelper().downloadAttachment(preview.getImage().get());
                    }
                }
            }
            if (message.getQuote().isPresent()) {
                final var quote = message.getQuote().get();

                if (quote.getAttachments() != null) {
                    for (var quotedAttachment : quote.getAttachments()) {
                        final var thumbnail = quotedAttachment.getThumbnail();
                        if (thumbnail != null) {
                            context.getAttachmentHelper().downloadAttachment(thumbnail);
                        }
                    }
                }
            }
        } else {
            if (message.getAttachments().isPresent()) {
                for (var attachment : message.getAttachments().get()) {
                    if (MimeUtils.LONG_TEXT.equals(attachment.getContentType()) && attachment.isPointer()) {
                        try {
                            context.getAttachmentHelper().retrieveAttachment(attachment, in -> {
                                final var longText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                                longTexts.put(attachment.asPointer().getRemoteId().toString(), longText);
                            });
                        } catch (IOException e) {
                            logger.warn("Failed to download long text attachment, ignoring", e);
                        }
                    }
                }
            }
        }
        if (message.getGiftBadge().isPresent()) {
            handleIncomingGiftBadge(message.getGiftBadge().get());
        }
        if (message.getProfileKey().isPresent()) {
            handleIncomingProfileKey(message.getProfileKey().get(), source.recipientId());
        }
        if (message.getSticker().isPresent()) {
            final var messageSticker = message.getSticker().get();
            final var stickerPackId = StickerPackId.deserialize(messageSticker.getPackId());
            var sticker = account.getStickerStore().getStickerPack(stickerPackId);
            if (sticker == null) {
                sticker = new StickerPack(stickerPackId, messageSticker.getPackKey());
                account.getStickerStore().addStickerPack(sticker);
            }
            if (!receiveConfig.ignoreStickers()) {
                context.getJobExecutor()
                        .enqueueJob(new RetrieveStickerPackJob(stickerPackId, messageSticker.getPackKey()));
            }
        }
        return new Pair<>(actions, longTexts);
    }

    private void handleIncomingGiftBadge(final SignalServiceDataMessage.GiftBadge giftBadge) {
        // TODO
    }

    private List<HandleAction> handleSignalServiceStoryMessage(
            SignalServiceStoryMessage message,
            RecipientId source,
            ReceiveConfig receiveConfig
    ) {
        var actions = new ArrayList<HandleAction>();
        if (message.getGroupContext().isPresent()) {
            handleGroupV2Context(message.getGroupContext().get(), receiveConfig.ignoreAvatars());
        }

        if (!receiveConfig.ignoreAttachments()) {
            if (message.getFileAttachment().isPresent()) {
                context.getAttachmentHelper().downloadAttachment(message.getFileAttachment().get());
            }
            if (message.getTextAttachment().isPresent()) {
                final var textAttachment = message.getTextAttachment().get();
                if (textAttachment.getPreview().isPresent()) {
                    final var preview = textAttachment.getPreview().get();
                    if (preview.getImage().isPresent()) {
                        context.getAttachmentHelper().downloadAttachment(preview.getImage().get());
                    }
                }
            }
        }

        if (message.getProfileKey().isPresent()) {
            handleIncomingProfileKey(message.getProfileKey().get(), source);
        }

        return actions;
    }

    private void handleGroupV2Context(final SignalServiceGroupV2 groupContext, final boolean ignoreAvatars) {
        final var groupMasterKey = groupContext.getMasterKey();

        context.getGroupHelper()
                .getOrMigrateGroup(groupMasterKey,
                        groupContext.getRevision(),
                        groupContext.hasSignedGroupChange() ? groupContext.getSignedGroupChange() : null,
                        ignoreAvatars);
    }

    private void handleIncomingProfileKey(final byte[] profileKeyBytes, final RecipientId source) {
        if (profileKeyBytes.length != 32) {
            logger.debug("Received invalid profile key of length {}", profileKeyBytes.length);
            return;
        }
        final ProfileKey profileKey;
        try {
            profileKey = new ProfileKey(profileKeyBytes);
        } catch (InvalidInputException e) {
            throw new AssertionError(e);
        }
        if (account.getSelfRecipientId().equals(source)) {
            this.account.setProfileKey(profileKey);
        }
        this.account.getProfileStore().storeProfileKey(source, profileKey);
    }

    private SignalServiceAddress getSenderAddress(SignalServiceEnvelope envelope, SignalServiceContent content) {
        final var serviceId = envelope.getSourceServiceId();
        if (!envelope.isUnidentifiedSender() && serviceId != null) {
            return new SignalServiceAddress(serviceId);
        } else if (content != null) {
            return content.getSender();
        } else {
            return null;
        }
    }

    private DeviceAddress getSender(SignalServiceEnvelope envelope, SignalServiceContent content) {
        final var serviceId = envelope.getSourceServiceId();
        if (!envelope.isUnidentifiedSender() && serviceId != null) {
            return new DeviceAddress(account.getRecipientResolver().resolveRecipient(serviceId),
                    serviceId,
                    envelope.getSourceDevice());
        } else {
            return new DeviceAddress(account.getRecipientResolver().resolveRecipient(content.getSender()),
                    content.getSender().getServiceId(),
                    content.getSenderDevice());
        }
    }

    private DeviceAddress getDestination(SignalServiceEnvelope envelope) throws InvalidMessageException {
        final var destination = envelope.getDestinationServiceId();
        if (destination == null || destination.isUnknown()) {
            throw new InvalidMessageException("Missing destination");
        }
        if (!account.getAci().equals(destination) && !account.getPni().equals(destination)) {
            throw new InvalidMessageException("Message not intended for this account");
        }
        return new DeviceAddress(account.getRecipientResolver().resolveRecipient(destination),
                destination,
                account.getDeviceId());
    }

    private record DeviceAddress(RecipientId recipientId, ServiceId serviceId, int deviceId) {}
}
