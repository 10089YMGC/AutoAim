package com.auto.autoaim;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

@Mod(AutoAim.MODID)
public class AutoAim {
    public static final String MODID = "autoaim";
    private static final KeyMapping AIM_KEY = new KeyMapping(
            "key.autoaim.aim",
            GLFW.GLFW_KEY_X,
            "category.autoaim.keys"
    );

    private boolean toggleState = false;
    private boolean keyWasPressed = false;
    private LivingEntity currentTarget = null;
    private static ForgeConfigSpec CONFIG_SPEC;
    private static Config CONFIG;

    public AutoAim() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerKey);

        var pair = new ForgeConfigSpec.Builder().configure(Config::new);
        CONFIG_SPEC = pair.getRight();
        CONFIG = pair.getLeft();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
    }

    private void registerKey(RegisterKeyMappingsEvent event) {
        event.register(AIM_KEY);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean keyPressed = AIM_KEY.isDown();
        boolean shouldAim = false;

        if (keyPressed != keyWasPressed) {
            System.out.println("按键状态变化: " + keyPressed);
        }

        switch (CONFIG.triggerMode.get()) {
            case HOLD -> shouldAim = keyPressed;
            case TOGGLE -> {
                if (keyPressed && !keyWasPressed) {
                    toggleState = !toggleState;
                }
                shouldAim = toggleState;
            }
        }
        keyWasPressed = keyPressed;

        if (shouldAim) {
            Optional<LivingEntity> target = findNearestEntity(player);
            target.ifPresentOrElse(
                    entity -> {
                        currentTarget = entity;
                        lookAtPosition(player, entity.getEyePosition());
                    },
                    () -> {
                        currentTarget = null;
                    }
            );
        } else {
            currentTarget = null;
        }
    }

    private Optional<LivingEntity> findNearestEntity(LocalPlayer player) {
        Vec3 center = player.getEyePosition();
        double radius = CONFIG.searchRadius.get();
        AABB area = new AABB(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius
        );

        return player.level().getEntitiesOfClass(
                        LivingEntity.class,
                        area,
                        e -> isValidTarget(e, player)
                ).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
    }

    private boolean isValidTarget(LivingEntity entity, LocalPlayer player) {
        // 基础过滤
        if (entity == player || !entity.isAlive() || entity.isInvisible()) {
            return false;
        }

        // 视线检测
        if (CONFIG.obstacleCheck.get()) {
            Vec3 start = player.getEyePosition();
            Vec3 end = entity.getEyePosition();
            BlockHitResult result = player.level().clip(new ClipContext(
                    start, end,
                    ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE,
                    player
            ));
            if (result.getType() == HitResult.Type.BLOCK) {
                return false;
            }
        }

        String typeStr = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString();
        @SuppressWarnings("unchecked")
        List<String> entityList = (List<String>) CONFIG.entityList.get();
        boolean valid = switch (CONFIG.listMode.get()) {
            case BLACKLIST -> !entityList.contains(typeStr);
            case WHITELIST -> entityList.contains(typeStr);
        };

        if (!valid) {
        }
        return valid;
    }

    private void lookAtPosition(LocalPlayer player, Vec3 targetPos) {
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 direction = targetPos.subtract(eyePos);

        if (direction.lengthSqr() < 1.0E-7D) return;
        direction = direction.normalize();

        double dx = direction.x;
        double dy = direction.y;
        double dz = direction.z;

        double yawRad = Math.atan2(dz, dx);
        double yaw = Mth.wrapDegrees(Math.toDegrees(yawRad) - 90.0);
        double pitch = Mth.wrapDegrees(Math.toDegrees(-Math.asin(dy)));

        player.setYRot((float) yaw);
        player.setXRot((float) Mth.clamp(pitch, -90.0F, 90.0F));

        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();

    }

    @SubscribeEvent
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES ||
                currentTarget == null ||
                !CONFIG.showTargetHighlight.get()) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        int color = CONFIG.highlightColor.get();
        float alpha = (float)((color >> 24) & 0xFF) / 255.0f;
        float red = (float)((color >> 16) & 0xFF) / 255.0f;
        float green = (float)((color >> 8) & 0xFF) / 255.0f;
        float blue = (float)(color & 0xFF) / 255.0f;

        AABB aabb = currentTarget.getBoundingBox();
        LevelRenderer.renderLineBox(
                poseStack,
                bufferSource.getBuffer(RenderType.lines()),
                aabb,
                red, green, blue, alpha
        );

        bufferSource.endBatch();
    }

    public static class Config {
        public final ForgeConfigSpec.EnumValue<TriggerMode> triggerMode;
        public final ForgeConfigSpec.DoubleValue searchRadius;
        public final ForgeConfigSpec.BooleanValue obstacleCheck;
        public final ForgeConfigSpec.EnumValue<ListMode> listMode;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> entityList;
        public final ForgeConfigSpec.BooleanValue showTargetHighlight;
        public final ForgeConfigSpec.IntValue highlightColor;
        public final ForgeConfigSpec.DoubleValue highlightWidth;

        public Config(ForgeConfigSpec.Builder builder) {
            builder.push("General Settings");
            triggerMode = builder.comment("Trigger mode: HOLD or TOGGLE")
                    .defineEnum("triggerMode", TriggerMode.HOLD);

            searchRadius = builder.comment("Search radius in blocks (1-50)")
                    .defineInRange("searchRadius", 10.0, 1.0, 50.0);

            obstacleCheck = builder.comment("Check for obstacles between player and target")
                    .define("obstacleCheck", true);

            listMode = builder.comment("Filter mode: BLACKLIST or WHITELIST")
                    .defineEnum("listMode", ListMode.BLACKLIST);

            entityList = builder.comment("Entity list (registry names)")
                    .defineList("entityList",
                            Arrays.asList("minecraft:armor_stand", "minecraft:bat"),
                            obj -> obj instanceof String);

            showTargetHighlight = builder.comment("Show target highlight effect")
                    .define("showHighlight", true);

            highlightColor = builder.comment("Highlight color (ARGB hex)")
                    .defineInRange("highlightColor", 0x80FF0000, Integer.MIN_VALUE, Integer.MAX_VALUE);

            highlightWidth = builder.comment("Highlight line width (0.1-5.0)")
                    .defineInRange("highlightWidth", 2.0, 0.1, 5.0);

            builder.pop();
        }
    }

    public enum TriggerMode { HOLD, TOGGLE }
    public enum ListMode { BLACKLIST, WHITELIST }
}