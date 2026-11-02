package org.telegram.messenger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.telegram.messenger.LunagramFeatureCatalog.Tier;

/** Actual production feature models; fake authority and in-memory Android preferences only. */
public final class LunagramFeatureGuards150Test {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void denied(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); } catch (SecurityException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        ApplicationLoader.applicationContext.root = new File(args[0]);
        UserConfig.selectedAccount = 0; UserConfig.ids[0] = 15001; UserConfig.ids[1] = 15002;
        LunagramSubscriptionAccess.reset();
        LumaGhostMode.setEnabled(0, true); LumaGhostMode.setScheduledSendEnabled(0, true);
        LumaDeletedMessages.setEnabled(0, true); LumaDeletedMessages.rememberDeleted(0, 55, 22);
        LumaAnonymousNumber.setDigits(0, "12345678"); LumaAnonymousNumber.setEnabled(0, true);
        LumaAnonymousNumber.setPriceTon(0, 15001, "94.3"); LumaAnonymousNumber.setPurchaseDate(0, 15001, 100000);
        LumaStarRating.setLevel(0, 88); LumaStarRating.setEnabled(0, true);
        LumaProfileVerification.setEnabled(0, true);
        LumaTextAnimation.setEnabled(true); LumaTextAnimation.setSpeedLevel(2);
        LumaMessageFormatting.setAutomaticStyle(LumaMessageFormatting.STYLE_BOLD);
        LumaDelayedSend.setEnabled(true); LumaDelayedSend.setDelayStep(12);
        BlackHoleNotes.save(0, 55, "saved private note");
        BlackHoleNotificationJournal.setEnabled(0, true);
        BlackHoleVault.setProtected(0, 55, true); BlackHoleVault.lockAll();
        Map<String, ?> before = LumaAccountData.preferences(0).getAll();
        Map<String, ?> globalBefore = MessagesController.getGlobalMainSettings().getAll();
        LunagramSubscriptionAccess.setTier(0, Tier.FREE);
        check(!LumaGhostMode.isEnabled(0) && !LumaGhostMode.isScheduledSendEnabled(0), "expired ghost effects off");
        check(!LumaDeletedMessages.shouldRetain(0, false, false, false), "new deletion retention off");
        check(LumaDeletedMessages.isDeleted(0, 55, 22), "existing tombstone retained/readable");
        check(!LumaAnonymousNumber.isEnabled(0) && "actual".equals(LumaAnonymousNumber.getDisplayPhone(0, 15001, "actual")), "local phone effect off");
        check(!LumaStarRating.isEnabled(0) && LumaStarRating.getDisplayRating(0, 15001, null) == null, "local level effect off");
        check(!LumaProfileVerification.showsPrefix(0, 15001, 15001, false, 0), "local verified prefix off");
        check(!LumaTextAnimation.isEnabled(), "typing off");
        check(LumaMessageFormatting.getAutomaticStyle() == 0 && LumaMessageFormatting.applyAutomaticStyle(0, "a", null) == null, "auto formatting off");
        check(LumaMessageFormatting.applyAutomaticStyle(1, "a", null).size() == 1, "target account authority independent of selected account");
        check(!LumaDelayedSend.isEnabled(0) && LumaDelayedSend.getDelayMs(0) == 0 && LumaDelayedSend.isEnabled(1), "undo bound to message account");
        check(!BlackHoleNotificationJournal.isEnabled(0), "new journal capture off");
        check(BlackHoleNotes.get(0, 55).equals("saved private note"), "existing note readable");
        check(BlackHoleVault.contains(0, 55) && BlackHoleVault.blocks(0, 55), "expiry NEVER unlocks existing vault");
        LumaGhostMode.setEnabled(0, true); LumaGhostMode.setScheduledSendEnabled(0, true);
        LumaDeletedMessages.setEnabled(0, true); LumaDeletedMessages.rememberDeleted(0, 55, 23);
        LumaAnonymousNumber.setDigits(0, "87654321"); LumaAnonymousNumber.setEnabled(0, true);
        check(!LumaAnonymousNumber.setPriceTon(0, 15001, "1") && !LumaAnonymousNumber.setPurchaseDate(0, 15001, 200000), "phone setters fail closed");
        LumaStarRating.setEnabled(0, true); LumaStarRating.setLevel(0, 99); LumaProfileVerification.setEnabled(0, true);
        LumaTextAnimation.setEnabled(true); LumaTextAnimation.setSpeedLevel(0); LumaTextAnimation.setBlurLevel(0);
        LumaTextAnimation.setHeightLevel(0); LumaTextAnimation.setSwipeMode(0);
        LumaMessageFormatting.setAutomaticStyle(2); LumaDelayedSend.setEnabled(true); LumaDelayedSend.setDelayStep(2);
        BlackHoleNotificationJournal.setEnabled(0, true); BlackHoleVault.setProtected(0, 56, true);
        try { BlackHoleNotes.save(0, 55, "changed"); throw new AssertionError("note mutation bypass"); } catch (SecurityException expected) { checks++; }
        check(before.equals(LumaAccountData.preferences(0).getAll()), "denied writes and effective getters never erase stored account data");
        check(globalBefore.equals(MessagesController.getGlobalMainSettings().getAll()), "denied global config keeps preferences");
        denied(() -> BlackHoleSettings.apply(0, java.util.Collections.singletonMap("format.style", 1)), "import requires own tier");
        Map<String,Object> mixed = new HashMap<>(); mixed.put("rating.level", 12); mixed.put("ghost.enabled", true);
        check(BlackHoleSettings.missingAccess(0, mixed) != null, "free account discloses missing paid feature before import");
        denied(() -> BlackHoleSettings.apply(0, mixed), "whole import preflight before writes");
        check(LumaStarRating.getLevel(0) == 88, "rejected import cannot partially change level");
        LunagramSubscriptionAccess.setTier(0, Tier.PRO);
        check(BlackHoleSettings.missingAccess(0, mixed) == null, "recognized former Pro includes every Ultra import feature");
        BlackHoleSettings.apply(0, java.util.Collections.singletonMap("rating.level", 89));
        check(LumaAccountData.preferences(0).getBoolean("luma_ghost_mode_enabled_", false), "unrelated subset import doesn't overwrite disabled effective ghost setting");
        LunagramSubscriptionAccess.setTier(0, Tier.ULTRA);
        check(LumaGhostMode.isEnabled(0) && LumaAnonymousNumber.isEnabled(0) && LumaTextAnimation.isEnabled(), "renewal restores existing effects without preference migration");
        check(LumaAnonymousNumber.getDigits(0).equals("12345678") && LumaAnonymousNumber.getPriceTon(0).equals("94.3"), "saved local profile values survive");
        LunagramSubscriptionAccess.setTier(0, Tier.FREE);
        LunagramSubscriptionAccess.setPrivacyUnknown(0, true);
        check(!LumaGhostMode.isEnabled(0) && LumaGhostMode.shouldSuppressActivity(0), "unknown billing protects armed ghost without enabling paid scheduling");
        check(!LumaGhostMode.isAutomaticScheduledSendEnabled(0), "unknown billing does not grant new paid scheduled sending");
        LumaGhostMode.setEnabled(0, false); LumaProfileVerification.setEnabled(0, false);
        check(!LumaGhostMode.shouldSuppressActivity(0), "explicit disable remains available during unknown billing");
        LumaGhostMode.setEnabled(0, true);
        check(!LumaGhostMode.shouldSuppressActivity(0), "unknown billing cannot newly enable ghost");
        BlackHoleNotes.save(0, 55, ""); BlackHoleVault.setProtected(0, 55, false);
        check(BlackHoleNotes.get(0,55).isEmpty() && !BlackHoleVault.contains(0,55), "explicit data removal remains available");
        check(!LumaAccountData.preferences(0).getBoolean("luma_ghost_mode_enabled_", true), "explicit disable permitted");
        check(!LumaGhostMode.isEnabled(1), "other account preferences unaffected");
        LunagramSubscriptionAccess.reset();
        System.out.println("PASS: " + checks + " actual feature runtime/mutation guards; no device, server lease or Keystore claim.");
    }
}
