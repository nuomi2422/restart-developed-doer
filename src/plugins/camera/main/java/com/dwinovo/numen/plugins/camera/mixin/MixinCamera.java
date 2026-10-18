package com.dwinovo.numen.plugins.camera.mixin;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管原版第三人称相机：实体跟着谁、镜头站哪、怎么防穿墙，全部由 {@link CameraRig} 定。
 *
 * <h2>为什么是 mixin 而不是 {@code Minecraft.setCameraEntity}</h2>
 * {@code setCameraEntity} 也能把镜头绑到同伴，但那样只能拿到原版那一种行为（背后固定
 * 4 格）。要的是「后面能改算法、变成不同追踪」，所以整段搬进来自己算。
 *
 * <h2>抄了哪些</h2>
 * 原版 {@code Camera} 只有三个方法扛起整个第三人称，全部按字节码逐条还原：
 * <ul>
 *   <li>{@code setup} —— 插值眼睛位置 + 按模式摆放（约 25 行）</li>
 *   <li>{@code move} —— 3 行：局部偏移经相机四元数旋到世界再叠加</li>
 *   <li>{@code getMaxZoom} —— 8 角点探针射线，取最近命中做穿墙拉近（约 20 行）</li>
 * </ul>
 *
 * <h2>刻意保留原版的两处细节</h2>
 * <ol>
 *   <li><b>partial-tick 插值</b>：位置取 {@code lerp(pt, xo, x)}，眼高取
 *       {@code lerp(pt, eyeHeightOld, eyeHeight)}。少了它镜头是 20fps 阶梯式跳动 ——
 *       这是抄算法时最容易丢、丢了最难查的一行。</li>
 *   <li><b>yaw 公式是 {@code atan2(-dx, dz)}</b>：因为 MC 朝向约定
 *       {@code dir.x = -sin(yaw)·cos(pitch)}。少了那个负号就整整差 180°。</li>
 * </ol>
 *
 * <h2>不打穿</h2>
 * 目标不在客户端实体表内（走出渲染距离）时 {@link CameraRig#resolveTarget()} 返回
 * null，此时<b>不取消</b>，让原版照常接管 —— 拿 null 硬算会让整帧渲染崩掉。
 */
@Mixin(Camera.class)
public abstract class MixinCamera {

    @Shadow private boolean initialized;
    @Shadow private BlockGetter level;
    @Shadow private Entity entity;
    @Shadow private boolean detached;
    @Shadow private float partialTickTime;
    @Shadow private Vec3 position;
    @Shadow private Vector3f forwards;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private Quaternionf rotation;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    // ── 主入口：替换整个 setup ─────────────────────────────────────────────
    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("HEAD"), cancellable = true)
    private void numencam$rigSetup(BlockGetter level, Entity vanillaEntity, boolean detached,
                                   boolean mirror, float partialTick, CallbackInfo ci) {
        if (!CameraRig.isActive()) return;

        Entity target = CameraRig.resolveTarget();
        if (target == null) {
            // 目标丢失：holdWhenLost 且已有机位 → 钉住原地只保持朝向
            if (CameraRig.holdWhenLost() && CameraRig.dampedPosition() != null) {
                this.initialized = true;
                this.level = level;
                this.entity = null;
                this.detached = true;
                this.partialTickTime = partialTick;
                this.setPosition(CameraRig.dampedPosition());
            }
            // 否则不取消，交还原版
            return;
        }

        this.initialized = true;
        this.level = level;
        this.entity = target;      // 后续 fluid/ClipContext 都以目标为准
        this.detached = true;
        this.partialTickTime = partialTick;

        switch (CameraRig.mode()) {
            case ORBIT -> numencam$orbit(target, partialTick);
            case LOCK -> numencam$lock(target);
            case CINEMATIC -> numencam$cinematic(target, partialTick);
            case SHOULDER, OFF -> numencam$shoulder(target, partialTick);
        }
        ci.cancel();
    }

    // ── 模式：越肩（= 原版第三人称，加了高度与肩部横移）─────────────────────
    @Unique
    private void numencam$shoulder(Entity target, float pt) {
        Vec3 eye = numencam$interpolatedEye(target, pt);
        this.setRotation(target.getViewYRot(pt), target.getViewXRot(pt));
        this.setPosition(eye);
        // move(x,y,z) 的局部轴：-x=后退, y=上, z=横移
        this.numencam$move(-numencam$maxZoom(CameraRig.distance()), CameraRig.height(), CameraRig.shoulder());
        CameraRig.damp(this.position, this.yRot, this.xRot, 1.0f);
    }

    // ── 模式：轨道（世界方位角固定，不跟目标转身）────────────────────────────
    @Unique
    private void numencam$orbit(Entity target, float pt) {
        Vec3 base = numencam$interpolatedFeet(target, pt);
        double rad = Math.toRadians(CameraRig.bearing());
        Vec3 cam = new Vec3(
                base.x + Math.cos(rad) * CameraRig.distance(),
                base.y + CameraRig.height(),
                base.z + Math.sin(rad) * CameraRig.distance());
        this.setPosition(cam);
        numencam$aimAt(cam, new Vec3(target.getX(), target.getY() + 1.5, target.getZ()));
        CameraRig.damp(this.position, this.yRot, this.xRot, 1.0f);
    }

    // ── 模式：固定机位（只转头）────────────────────────────────────────────
    @Unique
    private void numencam$lock(Entity target) {
        Vec3 cam = new Vec3(CameraRig.lockX(), CameraRig.lockY(), CameraRig.lockZ());
        this.setPosition(cam);
        numencam$aimAt(cam, new Vec3(target.getX(), target.getY() + 1.5, target.getZ()));
        CameraRig.damp(this.position, this.yRot, this.xRot, 1.0f);
    }

    // ── 模式：电影（越肩 + 俯角偏置 + 跨帧阻尼）──────────────────────────────
    @Unique
    private void numencam$cinematic(Entity target, float pt) {
        Vec3 eye = numencam$interpolatedEye(target, pt);
        this.setRotation(target.getViewYRot(pt), target.getViewXRot(pt) + CameraRig.pitchBias());
        this.setPosition(eye);
        this.numencam$move(-numencam$maxZoom(CameraRig.distance()), CameraRig.height(), CameraRig.shoulder());

        // 阻尼：朝当前位置收敛，lambda 越小越"重"（长镜不抖）
        CameraRig.damp(this.position, this.yRot, this.xRot, 0.15f);
        this.setPosition(CameraRig.dampedPosition());
    }

    // ── 抄自原版：partial-tick 插值的脚部位置 ────────────────────────────────
    @Unique
    private Vec3 numencam$interpolatedFeet(Entity t, float pt) {
        return new Vec3(
                Mth.lerp(pt, t.xo, t.getX()),
                Mth.lerp(pt, t.yo, t.getY()),
                Mth.lerp(pt, t.zo, t.getZ()));
    }

    // ── 抄自原版：partial-tick 插值的眼睛位置 ────────────────────────────────
    @Unique
    private Vec3 numencam$interpolatedEye(Entity t, float pt) {
        Vec3 f = numencam$interpolatedFeet(t, pt);
        return new Vec3(f.x, f.y + Mth.lerp(pt, this.eyeHeightOld, this.eyeHeight), f.z);
    }

    // ── 抄自原版 Camera#move（3 行）────────────────────────────────────────
    //  注意参数顺序：原版构造的是 new Vector3f(z, y, -x)，不是 (x, y, z)。
    @Unique
    private void numencam$move(float x, float y, float z) {
        Vector3f v = new Vector3f(z, y, -x).rotate(this.rotation);
        this.setPosition(this.position.add(v.x, v.y, v.z));
    }

    // ── 抄自原版 Camera#getMaxZoom：8 角点探针射线做穿墙拉近 ───────────────
    @Unique
    private float numencam$maxZoom(float maxZoom) {
        for (int i = 0; i < 8; i++) {
            // (i&1, (i>>1)&1, (i>>2)&1) 各映射 ±1 → 眼位周围 0.1 半宽的立方体 8 个角
            float ox = ((i & 1) * 2 - 1) * 0.1f;
            float oy = (((i >> 1) & 1) * 2 - 1) * 0.1f;
            float oz = (((i >> 2) & 1) * 2 - 1) * 0.1f;
            Vec3 start = this.position.add(ox, oy, oz);
            Vec3 end = start.add(new Vec3(this.forwards).scale(-maxZoom));
            BlockHitResult hit = this.level.clip(new ClipContext(
                    start, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, this.entity));
            if (hit.getType() != HitResult.Type.MISS) {
                float d = (float) hit.getLocation().distanceToSqr(this.position);
                if (d < Mth.square(maxZoom)) {
                    maxZoom = Mth.sqrt(d);
                }
            }
        }
        return maxZoom;
    }

    // ── 工具：由相机位瞄到目标点（yaw 用 atan2(-dx, dz)，别写反）────────────
    @Unique
    private void numencam$aimAt(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        this.setRotation(yaw, pitch);
    }
}