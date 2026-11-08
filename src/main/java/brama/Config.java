package brama;

import brama.block.GateSize;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Server-scoped tuning values, written to {@code saves/<world>/serverconfig/brama-server.toml}.
 *
 * <p>Server scope rather than common: {@code getDestroyProgress} runs on the client as well as the
 * server, so a client with different hardness values would visibly break a block and then watch it
 * pop back. Forge syncs server configs on join, which removes that mismatch. Seed a global default
 * from {@code defaultconfigs/brama-server.toml} to avoid re-tuning every new world.
 *
 * <p>Everything here is read at the point of use, never at class-init, because config values are not
 * available until well after registry setup.
 */
public final class Config {

    /** Per-gate-type balance. Each concrete gate block returns its own section. */
    public static final GateSettings WOODEN = new GateSettings();
    public static final GateSettings PORTCULLIS = new GateSettings();
    public static final GateSettings DRAWBRIDGE = new GateSettings();
    public static final GateSettings BASCULE_DRAWBRIDGE = new GateSettings();

    private static final Shared SHARED = new Shared();

    public static final ForgeConfigSpec SPEC = new ForgeConfigSpec.Builder().configure(builder -> {
        // The 0.0 siege resistances are deliberate; see the comment on siegeExplosionResistance.
        SHARED.define(builder);
        WOODEN.define(builder, "wooden", "wooden gate", 3, 30.0D, 4.0D, 0.0D, 20);
        PORTCULLIS.define(builder, "portcullis", "portcullis", 5, 50.0D, 6.0D, 0.0D, 40);
        DRAWBRIDGE.define(builder, "drawbridge", "drawbridge", 4, 35.0D, 5.0D, 0.0D, 30);
        BASCULE_DRAWBRIDGE.define(builder, "bascule_drawbridge", "bascule drawbridge", 4, 40.0D, 5.0D, 0.0D, 36);
        return SHARED;
    }).getRight();

    private Config() {
    }

    public static boolean externalBreaksDamageGates() {
        return SHARED.externalBreaksDamageGates.get();
    }

    public static boolean ownershipEnabled() {
        return SHARED.ownershipEnabled.get();
    }

    public static boolean unownedGatesPublic() {
        return SHARED.unownedGatesPublic.get();
    }

    public static boolean protectDisassembly() {
        return SHARED.protectDisassembly.get();
    }

    public static int adminPermissionLevel() {
        return SHARED.adminPermissionLevel.get();
    }

    public static boolean adminBypassRequiresCreative() {
        return SHARED.adminBypassRequiresCreative.get();
    }

    public static int hammerDurability() {
        return SHARED.hammerDurability.get();
    }

    public static boolean explosionsDamageGates() {
        return SHARED.explosionsDamageGates.get();
    }

    public static boolean creeperProof() {
        return SHARED.creeperProof.get();
    }

    /** Whether assembly should consider this footprint at all. */
    public static boolean sizeEnabled(GateSize size) {
        return SHARED.enabledSizes.get().contains(size.getSerializedName());
    }

    // ------------------------------------------------------------------ siege

    public static boolean autoClose() {
        return SHARED.autoClose.get();
    }

    public static double alarmRadius() {
        return SHARED.alarmRadius.get();
    }

    public static int scanInterval() {
        return SHARED.scanInterval.get();
    }

    public static boolean closeOnStrangers() {
        return SHARED.closeOnStrangers.get();
    }

    public static boolean trustClaimAllies() {
        return SHARED.trustClaimAllies.get();
    }

    public static boolean warnDefenders() {
        return SHARED.warnDefenders.get();
    }

    public static String claimProvider() {
        return SHARED.claimProvider.get();
    }

    public static int probeInterval() {
        return SHARED.probeInterval.get();
    }

    /** Balance for one gate type. */
    public static final class GateSettings {

        private ForgeConfigSpec.IntValue damageStages;
        private ForgeConfigSpec.DoubleValue gateHardness;
        private ForgeConfigSpec.DoubleValue looseBlockHardness;
        private ForgeConfigSpec.DoubleValue explosionResistance;
        private ForgeConfigSpec.DoubleValue siegeExplosionResistance;
        private ForgeConfigSpec.IntValue openTicks;

        private GateSettings() {
        }

        /**
         * Highest valid {@code damage} value under the current setting, clamped to the property's
         * compile-time range so lowering the stage count cannot strand a gate above the new maximum
         * and leave it unbreakable.
         */
        public int maxDamage() {
            return Math.min(this.damageStages.get() - 1, GateSize.MAX_DAMAGE_STAGES - 1);
        }

        public double gateHardness() {
            return this.gateHardness.get();
        }

        public double looseBlockHardness() {
            return this.looseBlockHardness.get();
        }

        public double explosionResistance() {
            return this.explosionResistance.get();
        }

        public double siegeExplosionResistance() {
            return this.siegeExplosionResistance.get();
        }

        public int openTicks() {
            return this.openTicks.get();
        }

        private void define(ForgeConfigSpec.Builder builder, String section, String label,
                            int stages, double hardness, double loose, double siegeResistance, int ticks) {
            builder.comment("Balance for the " + label + ".").push(section);
            this.damageStages = builder
                    .comment("Hits needed to destroy an assembled " + label + ".",
                            "Capped at " + GateSize.MAX_DAMAGE_STAGES + ": the damage blockstate property has that many",
                            "slots reserved at registration and cannot grow from a config.")
                    .defineInRange("damageStages", stages, 1, GateSize.MAX_DAMAGE_STAGES);
            this.gateHardness = builder
                    .comment("Mining hardness of the assembled gate, per damage stage.",
                            "Each stage of wear then breaks twice as fast as the one before.")
                    .defineInRange("gateHardness", hardness, 0.1D, 500.0D);
            this.looseBlockHardness = builder
                    .comment("Mining hardness of a single unassembled block.")
                    .defineInRange("looseBlockHardness", loose, 0.1D, 500.0D);
            this.explosionResistance = builder
                    .comment("Blast resistance used while the gate is immune to a given explosion.",
                            "Only affects how much of the blast the gate soaks up before it carries on",
                            "past. Whether the gate survives is decided separately, so this does not need",
                            "to be large for immunity to hold.")
                    .defineInRange("explosionResistance", 1200.0D, 0.0D, 5000.0D);
            this.siegeExplosionResistance = builder
                    .comment("Blast resistance used while the gate is vulnerable. Zero by default, and",
                            "that is not an oversight.",
                            "",
                            "Raising it cannot make a gate tougher: a blast that reaches the gate costs",
                            "exactly one damage stage whatever this says. All it changes is whether the",
                            "blast reaches at all, and the margins are tighter than they look. Minecraft",
                            "charges an explosion (resistance + 0.3) * 0.3 to enter a block, and cannon",
                            "mods fire far weaker charges than TNT -- 1.33 to 2.0 against TNT's 4.0. At a",
                            "resistance of 3 a 1.33 charge is left with nothing before it arrives, so the",
                            "shell lands, detonates, and the gate does not so much as flinch.",
                            "",
                            "Zero keeps that from ever happening. Set toughness with damageStages and",
                            "gateHardness, which is what they are for.",
                            "",
                            "The cost of zero is that a blast is no longer absorbed and carries on through",
                            "the gateway. Raise it if you would rather the gate shielded what stands",
                            "behind it, and accept that light artillery may then bounce off.")
                    .defineInRange("siegeExplosionResistance", siegeResistance, 0.0D, 100.0D);
            this.openTicks = builder
                    .comment("Ticks for the gate to open fully. 20 ticks is one second.")
                    .defineInRange("openTicks", ticks, 1, 200);
            builder.pop();
        }
    }

    private static final class Shared {

        private ForgeConfigSpec.BooleanValue externalBreaksDamageGates;
        private ForgeConfigSpec.BooleanValue ownershipEnabled;
        private ForgeConfigSpec.BooleanValue unownedGatesPublic;
        private ForgeConfigSpec.BooleanValue protectDisassembly;
        private ForgeConfigSpec.IntValue adminPermissionLevel;
        private ForgeConfigSpec.BooleanValue adminBypassRequiresCreative;
        private ForgeConfigSpec.ConfigValue<List<? extends String>> enabledSizes;
        private ForgeConfigSpec.IntValue hammerDurability;

        private ForgeConfigSpec.BooleanValue explosionsDamageGates;
        private ForgeConfigSpec.BooleanValue creeperProof;

        private ForgeConfigSpec.BooleanValue autoClose;
        private ForgeConfigSpec.DoubleValue alarmRadius;
        private ForgeConfigSpec.IntValue scanInterval;
        private ForgeConfigSpec.BooleanValue closeOnStrangers;
        private ForgeConfigSpec.BooleanValue trustClaimAllies;
        private ForgeConfigSpec.BooleanValue warnDefenders;
        private ForgeConfigSpec.ConfigValue<String> claimProvider;
        private ForgeConfigSpec.IntValue probeInterval;

        void define(ForgeConfigSpec.Builder builder) {
            builder.comment("Behaviour shared by every gate type.").push("general");
            this.externalBreaksDamageGates = builder
                    .comment("Battering rams, WorldEdit and other mods cost the gate a damage stage and the",
                            "removed block is restored. Set false to instead disassemble the gate into loose blocks.")
                    .define("externalBreaksDamageGates", true);
            this.ownershipEnabled = builder
                    .comment("Master switch for locking. When false every gate is open to everyone.")
                    .define("ownershipEnabled", true);
            this.unownedGatesPublic = builder
                    .comment("Gates with no owner - assembled before ownership existed, or by a command block -",
                            "stay open to everyone. Set false to lock them to admins only.")
                    .define("unownedGatesPublic", true);
            this.protectDisassembly = builder
                    .comment("Taking a gate apart with the hammer requires owner or co-owner.",
                            "With this off, anyone can disassemble a gate and reassemble it to claim ownership.")
                    .define("protectDisassembly", true);
            this.adminPermissionLevel = builder
                    .comment("Vanilla operator level that bypasses every gate's access list.")
                    .defineInRange("adminPermissionLevel", 2, 0, 4);
            this.adminBypassRequiresCreative = builder
                    .comment("An operator only bypasses a gate's access list while in creative mode.",
                            "",
                            "On by default, and it closes a real hole: operator level alone used to open every",
                            "locked gate on the map, so a moderator playing the server's own survival world",
                            "walked through other people's castles without so much as a message to the owner.",
                            "Creative mode is the mode you are in when you are administrating rather than",
                            "playing, which makes it the honest test of intent.",
                            "",
                            "It covers the /gate commands too, and has to: an operator who could still run",
                            "/gate op in survival would simply grant themselves the gate and open it.",
                            "",
                            "Set false to go back to plain permission-level bypass.")
                    .define("adminBypassRequiresCreative", true);
            this.enabledSizes = builder
                    .comment("Footprints the hammer will assemble, for every gate type. Removing one does not",
                            "affect gates that already exist. Valid values: " + String.join(", ", allSizes()))
                    .defineList("enabledSizes", allSizes(), value -> allSizes().contains(value));
            this.hammerDurability = builder
                    .comment("Uses before a Gate Hammer breaks.")
                    .defineInRange("hammerDurability", 256, 1, 10000);
            builder.pop();

            builder.comment("How gates react to blasts, for every gate type.",
                            "The defaults are set up for siege mods: cannons, bombards and TNT all cost a",
                            "gate one damage stage per hit, whatever mod fired them, while creepers cannot",
                            "breach a gate at all.").push("explosions");
            this.explosionsDamageGates = builder
                    .comment("Whether an explosion costs an assembled gate one damage stage.",
                            "Turn this off and gates ignore every blast, siege engines included.")
                    .define("explosionsDamageGates", true);
            this.creeperProof = builder
                    .comment("Creepers can never breach a gate, even when explosions are allowed to damage them.",
                            "Judged by what set the blast off, so a creeper is harmless to gates while a",
                            "cannon standing in the same spot is not.")
                    .define("creeperProof", true);
            builder.pop();

            builder.comment("Gates that watch their own gateway: an enemy walks up to an open gate and it",
                            "shuts in their face.",
                            "",
                            "Who counts as an enemy is answered in three steps, in order:",
                            "  1. Anyone who may open the gate never does - owner, co-owners, trusted players,",
                            "     trusted scoreboard teams, and the owner's allies according to a claim mod.",
                            "  2. Anyone on the owner's list from /gate enemy always does.",
                            "  3. Otherwise the claim mod decides, if there is one: standing inside the gate",
                            "     owner's claim without being a member or an ally of it is enough.",
                            "With no claim mod and nobody listed, nothing happens unless closeOnStrangers is",
                            "on - a gate in the wilderness has no way of telling an enemy from a passer-by.")
                    .push("siege");
            this.autoClose = builder
                    .comment("Master switch for the whole siege watch. Off means gates only ever move when",
                            "somebody works them by hand.")
                    .define("autoClose", true);
            this.alarmRadius = builder
                    .comment("How close an enemy has to come, in blocks, measured from the gate's bottom corner.",
                            "24 is roughly bowshot: far enough that the gate is shut before they arrive, close",
                            "enough that it is not reacting to someone crossing the valley.")
                    .defineInRange("alarmRadius", 24.0D, 1.0D, 128.0D);
            this.scanInterval = builder
                    .comment("Ticks between sweeps. 20 is once a second. The sweep walks the online players",
                            "rather than the gates, so the cost follows how many people are on the server and",
                            "not how many gates have been built.")
                    .defineInRange("scanInterval", 20, 1, 200);
            this.closeOnStrangers = builder
                    .comment("With no claim information for a gate, treat anybody who cannot open it as an enemy.",
                            "",
                            "Off by default. On a server with no claim mod it is the only way to get automatic",
                            "gates at all, but it is blunt: every visitor, trader and new player sets the gate",
                            "off. Prefer /gate trust for the people you expect and /gate enemy for the ones",
                            "you do not.")
                    .define("closeOnStrangers", false);
            this.trustClaimAllies = builder
                    .comment("Members and allies of the gate owner's team or party, as the claim mod sees them,",
                            "may open the gate without being trusted on it one by one.",
                            "",
                            "This is the half of the claim integration that lets people in, and it is the half",
                            "worth understanding before turning it on: an alliance signed in the claim mod's",
                            "own menus immediately opens every gate its members own. Turn it off to keep each",
                            "gate's list of who may open it strictly its own.")
                    .define("trustClaimAllies", true);
            this.warnDefenders = builder
                    .comment("Tell the gate's owner and co-owners, if they are online and nearby, when their",
                            "gate shuts itself on somebody. Once per gate at most, however long the enemy",
                            "stands there.")
                    .define("warnDefenders", true);
            this.claimProvider = builder
                    .comment("Which claim mod to ask. One of:",
                            "  auto     - whichever of the mods below is installed, else probe",
                            "  ftbchunks, openpartiesandclaims, flan",
                            "             - that mod's own API, which also answers who is allied with whom",
                            "  probe    - ask no mod in particular. Instead, before deciding that somebody is",
                            "             an enemy, offer the server a cancellable interaction on the gate in",
                            "             their name and see whether anything refuses it. Every protection mod",
                            "             that guards blocks refuses on a claim it does not think they belong",
                            "             in, which makes this work with mods this one has never heard of -",
                            "             at the cost of knowing nothing about alliances, and of other mods",
                            "             seeing an interaction that never really happened. Throttled by",
                            "             probeInterval.",
                            "  none     - no claim mod at all. Only /gate enemy and closeOnStrangers remain.")
                    .define("claimProvider", "auto");
            this.probeInterval = builder
                    .comment("Ticks before the probe provider asks about the same player and gate again.",
                            "Only used when claimProvider ends up as probe. 100 is five seconds.")
                    .defineInRange("probeInterval", 100, 20, 1200);
            builder.pop();
        }

        private static List<String> allSizes() {
            return Arrays.stream(GateSize.values()).map(GateSize::getSerializedName).collect(Collectors.toList());
        }
    }
}
