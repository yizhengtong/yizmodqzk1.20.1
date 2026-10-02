#version 150

// 3D 体积火焰 —— 顶点着色器
// 几何用<b>相机相对世界坐标</b>生成（{@code worldPos - cameraPos}，与 LightningRenderer 同一约定）：
//   1) ModelViewMat 只含视图旋转（PoseStack 无平移），顶点位置即"相机相对坐标"；
//   2) 该空间里相机位于原点 → 片元侧 uCameraWorld 恒为 (0,0,0)，
//      射线方向 normalize(vWorldPos - ro) 对凸包围盒正好是主视线（省掉矩阵求逆）；
//   3) 噪声采样落在世界空间（相机相对与原点的差是常量平移，不影响形态）→ 火焰穿过世界流动，
//      同一次施法的多个火墙不会各自闪烁。

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vWorldPos;
out vec4 vColor;

void main() {
    vWorldPos = Position;
    vColor    = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
