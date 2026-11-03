#!/usr/bin/env python3
"""Apply v1.26 behaviour to the reconstructed v1.25 engine.

Keep original recovery patches immutable. Fail closed if the expected source
shape changes; never silently ship the legacy confirmation flow.
"""
from pathlib import Path
import re

root = Path("src/mokafeefah")
service_path = root / "app/src/main/java/com/mokafeefah/clicker/ClickerService.java"
activity_path = root / "app/src/main/java/com/mokafeefah/clicker/MainActivity.java"
gradle_path = root / "app/build.gradle"

service = service_path.read_text()
activity = activity_path.read_text()
gradle = gradle_path.read_text()

def replace_once(haystack, old, new, label):
    n = haystack.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, got {n}")
    return haystack.replace(old, new, 1)

# The new Mawadda interest action is a single tap. No "نعم" step.
# A "موافق" button is only clicked after independently verifying the
# "already in interest list" dialog.
activity = replace_once(activity,
    '"إهتمام", "نعم", "موافق", "", profileKeywords,',
    '"إهتمام", "__v126_unused_yes__", "موافق", "", profileKeywords,',
    "v126 bot configuration")
activity_path.write_text(activity)

# Remove v1.25's two unconditional full-tree confirmation searches, which
# could tap unrelated "موافق" buttons and slow down a long WebView session.
start_marker = "        if (fastCachedLikeCandidate == null) {"
like_marker = "        AccessibilityNodeInfo likeBtn = findUnprocessedClickable(roots, config.likeText);"
look_start = service.index("    private long handleLookLike(")
start = service.index(start_marker, look_start)
end = service.index(like_marker, start)
old = service[start:end]
if "config.yesText" not in old or "config.closeText" not in old or "commitPendingLike" not in old:
    raise SystemExit("Unexpected 1.25 confirmation preamble, refusing v1.26 upgrade")
service = service[:start] + (
    "        // v1.26: direct interest, no unconditional YES/OK scans.\n"
    "        // The state dispatcher handles verified duplicate-list dialogs.\n\n"
) + service[end:]

# Divert AFTER_LIKE to the direct-action resolver; leave the 1.20 methods
# unmodified for archaeological comparison and recovery.
for state, method in (
    ("STATE_LOOK_LIKE", "handleV126Look"),
    ("STATE_AFTER_LIKE", "handleV126DirectInterest"),
    ("STATE_AFTER_YES", "handleV126DirectInterest"),
    ("STATE_MUST_SCROLL", "handleV126MustScroll"),
):
    pat = re.compile(
        r"(case " + state + r":\s*)return\s+(handleLookLike|handleAfterLike|handleAfterYes|handleMustScroll)\(roots,\s*now\);"
    )
    service, num = pat.subn(lambda m: m.group(1) + f"return {method}(roots, now);", service, count=1)
    if num != 1:
        raise SystemExit(f"Missing state dispatch: {state}")

v126_methods = r'''
    // v1.26. Kept separate from the 1.20 recovery engine and v1.25 caches.
    private long v126LastHeartbeatMs = 0L;
    private long v126LastGrowthMs = 0L;
    private long v126LastStallNoteMs = 0L;
    private int v126LastObservedCount = -1;
    private int v126LastMilestone = 0;
    private long v126DuplicateSinceMs = 0L;
    private boolean v126DuplicateBackAttempted = false;
    private long v126LastDuplicateLogMs = 0L;

    // Bounded, private on-device telemetry. Only aggregated counters and
    // reasons are persisted, never member names, page text or passwords.
    private void v126PersistHealth(String reason, long now) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File report = new java.io.File(dir, "mokafeefah_v126_health.txt");
            if (report.exists() && report.length() > 128 * 1024) {
                java.io.File older = new java.io.File(dir, "mokafeefah_v126_health_previous.txt");
                if (older.exists()) older.delete();
                report.renameTo(older);
            }
            try (java.io.FileWriter out = new java.io.FileWriter(report, true)) {
                out.write(now + " | " + reason
                        + " | count=" + likesCount.get()
                        + " skip=" + skippedCount.get()
                        + " state=" + stateName(state)
                        + " staleViewport=" + unchangedViewportScrolls
                        + " noLikeTicks=" + consecutiveNoLikeControlTicks
                        + " errors=" + consecutiveTickErrors
                        + " tracked=" + sessionMemberIds.size()
                        + "\n");
            }
        } catch (Throwable ignored) {
            // Diagnostics must never interrupt the automation thread.
        }
    }

    private void v126AppendHealthToDiagnostic(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File report = new java.io.File(dir, "mokafeefah_v126_health.txt");
            if (!report.isFile()) return;
            try (java.io.FileReader in = new java.io.FileReader(report);
                 java.io.FileWriter out = new java.io.FileWriter(path, true)) {
                out.write("\n\n===== V1.26 PERSISTED HEALTH / NO MEMBER DATA =====\n");
                char[] buf = new char[2048];
                int read;
                int total = 0;
                while (total < 131072 && (read = in.read(buf)) != -1) {
                    int toWrite = Math.min(read, 131072 - total);
                    out.write(buf, 0, toWrite);
                    total += toWrite;
                }
            }
        } catch (Throwable t) {
            diagEvent("V126-HEALTH-APPEND-ERROR: " + t.getClass().getSimpleName());
        }
    }

    private void v126SessionHealth(long now) {
        int count = likesCount.get();
        if (v126LastGrowthMs == 0L) v126LastGrowthMs = now;
        if (v126LastObservedCount < 0 || count > v126LastObservedCount) {
            v126LastGrowthMs = now;
            v126LastObservedCount = count;
        }
        int milestone = count / 50;
        if (count >= 50 && milestone > v126LastMilestone) {
            v126LastMilestone = milestone;
            diagEvent("V126-CHECKPOINT: count=" + count
                    + " skipped=" + skippedCount.get()
                    + " membersTracked=" + sessionMemberIds.size()
                    + " state=" + stateName(state)
                    + " viewportStalls=" + unchangedViewportScrolls
                    + " noLikeTicks=" + consecutiveNoLikeControlTicks);
            v126PersistHealth("CHECKPOINT", now);
        }
        if (v126LastHeartbeatMs == 0L || now - v126LastHeartbeatMs >= 30_000L) {
            v126LastHeartbeatMs = now;
            diagEvent("V126-HEALTH: count=" + count
                    + " sinceGrowthMs=" + Math.max(0L, now - v126LastGrowthMs)
                    + " state=" + stateName(state)
                    + " processedBounds=" + processedBounds.size()
                    + " memberIds=" + sessionMemberIds.size()
                    + " noLikeTicks=" + consecutiveNoLikeControlTicks
                    + " unchangedScrolls=" + unchangedViewportScrolls
                    + " tickErrors=" + consecutiveTickErrors);
        }
        if (now - v126LastGrowthMs >= 90_000L
                && now - v126LastStallNoteMs >= 60_000L) {
            v126LastStallNoteMs = now;
            String cause;
            if (v126DuplicateSinceMs != 0L) cause = "DUPLICATE_POPUP";
            else if (consecutiveTickErrors > 0) cause = "ENGINE_EXCEPTION";
            else if (unchangedViewportScrolls > 0) cause = "VIEWPORT_NOT_MOVING";
            else if (consecutiveNoLikeControlTicks > 0) cause = "NO_VISIBLE_INTEREST";
            else cause = "AWAITING_UI_OR_NETWORK";
            diagEvent("V126-STALL-DIAGNOSIS: cause=" + cause
                    + " count=" + count
                    + " ageMs=" + (now - v126LastGrowthMs)
                    + " noLikeTicks=" + consecutiveNoLikeControlTicks
                    + " unchangedScrolls=" + unchangedViewportScrolls
                    + " errors=" + consecutiveTickErrors);
            v126PersistHealth("STALL:" + cause, now);
        }
    }

    // A modal must be positively identified by its own text, not merely
    // by the common label "موافق". The snapshot is already bounded/visible.
    private boolean v126HasAlreadyInterestedDialog(List<AccessibilityNodeInfo> roots) {
        if (currentProbe != null) {
            boolean duplicate = currentProbe.countMatches("مضاف") > 0;
            boolean context = currentProbe.countMatches("الاهتمام") > 0
                    || currentProbe.countMatches("الإهتمام") > 0
                    || currentProbe.countMatches("إضافته مرة") > 0;
            if (duplicate && context) return true;
        }
        // Android native text index is a targeted fallback for truncated
        // snapshots / separate modal windows; never traverse all 5000 nodes.
        if (roots == null) return false;
        for (AccessibilityNodeInfo root : roots) {
            if (root == null) continue;
            try {
                List<AccessibilityNodeInfo> hits =
                        root.findAccessibilityNodeInfosByText("مضاف");
                if (hits == null) continue;
                for (AccessibilityNodeInfo hit : hits) {
                    if (hit == null || !hit.isVisibleToUser()) continue;
                    String raw = String.valueOf(hit.getText())
                            + " " + String.valueOf(hit.getContentDescription());
                    String message = normalizeArabic(raw);
                    if (message.contains("مضاف")
                            && (message.contains("اهتمام")
                            || message.contains("اضافته")
                            || message.contains("مره اخري"))) return true;
                }
            } catch (Throwable ignore) {}
        }
        return false;
    }

    // Returns -1 if there is no matching modal, otherwise a wait interval.
    private long v126HandleDuplicateDialog(List<AccessibilityNodeInfo> roots, long now) {
        if (!v126HasAlreadyInterestedDialog(roots)) {
            v126DuplicateSinceMs = 0L;
            v126DuplicateBackAttempted = false;
            return -1L;
        }
        clearViewportLikeNodeCache();
        if (v126DuplicateSinceMs == 0L) {
            v126DuplicateSinceMs = now;
            v126DuplicateBackAttempted = false;
            diagEvent("V126-DUPLICATE-POPUP: identified by visible error message; looking for موافق");
        }
        AccessibilityNodeInfo ok = findClickableInAll(roots, "موافق");
        if (ok != null && performClick(ok)) {
            if (pendingLikeStartedMs != 0L) {
                clearPendingLike("v126 already in interest list");
            } else {
                diagEvent("V126-LATE-DUPLICATE: no pending request; review last tentative direct count");
            }
            skippedCount.incrementAndGet();
            lastButtonFoundMs = now;
            diagEvent("V126-DUPLICATE-POPUP: clicked موافق and skipped duplicate");
            v126DuplicateSinceMs = 0L;
            v126DuplicateBackAttempted = false;
            transitionTo(STATE_MUST_SCROLL);
            return 700L;
        }
        // One guarded BACK only if the same independently verified
        // duplicate modal stays stuck for over eight seconds.
        if (!v126DuplicateBackAttempted && now - v126DuplicateSinceMs > 8_000L) {
            v126DuplicateBackAttempted = true;
            boolean sent = false;
            try { sent = performGlobalAction(GLOBAL_ACTION_BACK); }
            catch (Throwable ignore) {}
            if (sent) {
                clearPendingLike("v126 duplicate popup back fallback");
                skippedCount.incrementAndGet();
                lastButtonFoundMs = now;
                diagEvent("V126-DUPLICATE-RECOVERY: guarded BACK sent=true");
                v126DuplicateSinceMs = 0L;
                transitionTo(STATE_LOOK_LIKE);
                return 900L;
            }
        }
        if (now - v126LastDuplicateLogMs > 3_000L) {
            v126LastDuplicateLogMs = now;
            diagEvent("V126-DUPLICATE-STILL-OPEN: موافق not clickable yet");
        }
        return 400L;
    }

    private long handleV126Look(List<AccessibilityNodeInfo> roots, long now) {
        v126SessionHealth(now);
        long popup = v126HandleDuplicateDialog(roots, now);
        if (popup >= 0L) return popup;
        return handleLookLike(roots, now);
    }

    private long handleV126DirectInterest(List<AccessibilityNodeInfo> roots, long now) {
        v126SessionHealth(now);
        long popup = v126HandleDuplicateDialog(roots, now);
        if (popup >= 0L) return popup;
        // The app no longer opens a YES confirmation. Wait briefly for a
        // server duplicate response; count the direct tap only afterward.
        if (now - stateChangedAt < 1_800L) return 350L;
        if (pendingLikeStartedMs != 0L) {
            commitPendingLike(now, "v1.26 direct-interest tap (no confirmation)");
            diagEvent("V126-DIRECT: committed attempted interest after modal grace");
        } else {
            diagEvent("V126-DIRECT: no pending tap; moving to verified scroll");
        }
        lastButtonFoundMs = now;
        transitionTo(STATE_MUST_SCROLL);
        return 350L;
    }

    private long handleV126MustScroll(List<AccessibilityNodeInfo> roots, long now) {
        v126SessionHealth(now);
        long popup = v126HandleDuplicateDialog(roots, now);
        if (popup >= 0L) return popup;
        return handleMustScroll(roots, now);
    }

'''
anchor="    private long handleLookLike(List<AccessibilityNodeInfo> roots, long now) {"
service=replace_once(service,anchor,v126_methods+anchor,"v126 inserted methods")
service = replace_once(service,
    "            lastDumpPath = path;\n            return path;",
    "            lastDumpPath = path;\n            v126AppendHealthToDiagnostic(path);\n            return path;",
    "Attach persisted health to shared diagnostic")
service_path.write_text(service)

gradle=replace_once(gradle,"versionCode 46","versionCode 47","versionCode")
gradle=replace_once(gradle,"versionName '1.25.0-event-cache-engine'",
                   "versionName '1.26.0-direct-interest-diagnostics'","versionName")
gradle_path.write_text(gradle)

# Fail-closed static checks; the remote CI still compiles both APK variants.
for literal in ("handleV126DirectInterest(roots, now)", "V126-STALL-DIAGNOSIS:",
                "V126-DUPLICATE-POPUP:", "versionCode 47"):
    if literal not in (service + gradle):
        raise SystemExit(f"v1.26 QA marker missing: {literal}")
if '"نعم", "موافق"' in activity or 'return handleAfterLike(roots, now)' in service:
    raise SystemExit("Legacy confirmation flow remains active")
if "applicationId 'com.mokafeefah.clicker'" not in gradle:
    raise SystemExit("Package ID changed, refusing upgrade")
print("V1.26 DIRECT-INTEREST MIGRATION CHECKS: PASS")
print("Package preserved; versionCode=47; release signing unchanged by migration")
