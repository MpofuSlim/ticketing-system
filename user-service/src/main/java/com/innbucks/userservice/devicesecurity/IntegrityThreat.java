package com.innbucks.userservice.devicesecurity;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The freeRASP findings DTX acts on (contract §6.1, §8.4, §8.5), recognised by
 * freeRASP's own callback names and a few plain-English spellings. Anything else
 * is recorded but not acted on.
 */
public enum IntegrityThreat {
    /** Root or jailbreak — an integrity hold, lifted when the check passes. */
    ROOT,
    /** Frida and other hooking frameworks — a ban. */
    HOOKS,
    /** Tampered app signature — a ban. */
    TAMPER,
    /** Automation (accessibility-driven scripts) — a ban. */
    AUTOMATION,
    /** Known malware — a ban. */
    MALWARE,
    /** Emulator — an OTP, and no cooling exemption. */
    EMULATOR,
    /** Debugger attached — an OTP. */
    DEBUGGER;

    /** Classifies the app's {@code integrity.threats[]}. Unknown values and "unofficial store" are ignored. */
    public static Set<IntegrityThreat> classify(List<String> threats) {
        Set<IntegrityThreat> out = EnumSet.noneOf(IntegrityThreat.class);
        if (threats == null) return out;
        for (String raw : threats) {
            if (raw == null) continue;
            String t = raw.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
            switch (t) {
                case "privilegedaccess", "root", "rooted", "jailbreak", "jailbroken" -> out.add(ROOT);
                case "hooks", "hook", "frida", "hooking" -> out.add(HOOKS);
                case "appintegrity", "tamper", "tampered", "tamperedsignature", "signature" -> out.add(TAMPER);
                case "automation" -> out.add(AUTOMATION);
                case "malware" -> out.add(MALWARE);
                case "simulator", "emulator" -> out.add(EMULATOR);
                case "debug", "debugger" -> out.add(DEBUGGER);
                default -> {
                    // unofficialStore is deliberately ignored: the APK is distributed directly.
                }
            }
        }
        return out;
    }

    /** Findings that ban the device (§8.5, first row). */
    public static final Set<IntegrityThreat> BANNING = EnumSet.of(HOOKS, TAMPER, AUTOMATION, MALWARE);
}
