package net.minecraft.client.yiz.modelmirror;

import net.minecraft.world.entity.Entity;

/**
 * 逐实体逐帧的镜像参数：由 {@link MirrorLayer#prepare} 产出、由镜像通道保存，顶点副本与刷帧 uniform 都从这里取值。
 *
 * <p>为什么用不可变 record 而不是"往通道上逐个 set"：通道会被多个消费者共用，
 * 若干个 {@code setXxx} 之间存在"改了一半"的中间态可能（虽然当前都在渲染线程顺序执行）；
 * 一次性交出整帧参数就没有这种状态，也天然保证"顶点写入用的 alpha"与"刷帧用的 alpha"是同一帧的值。</p>
 *
 * @param entity 正在渲染的实体（顶点/状态都以它为准）
 * @param dx     实体渲染位置 X（<b>相对相机</b>，含插值）：加回相机世界坐标即实体脚部世界坐标
 * @param dy     实体渲染位置 Y（相对相机）
 * @param dz     实体渲染位置 Z（相对相机）
 * @param yaw    实体渲染偏航（度）：把采样点反旋转到身体坐标系，图案才钉在身体上
 * @param alpha  顶点副本的顶点色 alpha（0~1）：整体淡入淡出（火焰的寿命淡出 / 闪电的渐显渐隐）
 */
public record MirrorFrame(Entity entity, double dx, double dy, double dz, float yaw, float alpha) {
}
