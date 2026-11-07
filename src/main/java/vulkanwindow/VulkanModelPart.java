package vulkanwindow;

import basewindow.*;
import basewindow.transformation.AxisRotation;
import basewindow.transformation.Rotation;

import java.util.ArrayList;
import java.util.HashMap;

/** CPU-recorded model geometry for the Vulkan backend. */
public class VulkanModelPart extends ModelPart
{
    public VulkanGameWindow window;

    public VulkanModelPart(VulkanGameWindow window) { super(window); }

    public VulkanModelPart(VulkanGameWindow window, Model model, ArrayList<Shape> shapes, Model.Material material)
    {
        super(window, model, shapes, material);
    }

    @Override public void setWindow(BaseWindow w) { this.window = (VulkanGameWindow) w; }

    @Override public void setSkin(HashMap<String, String> skins) { if (parent != null) parent.setSkin(skins); }

    @Override public void setSkin(String tex) { if (parent != null) parent.setSkin(tex); }

    @Override
    public void draw(double x, double y, double z, double sx, double sy, double sz, AxisRotation[] rotations, boolean depthTest)
    {
        // Most game models use the yaw/pitch/roll overload. Apply axis rotations in the
        // same matrix stack so callers of the less common overload still get geometry.
        window.addMatrix();
        try
        {
            window.transform(translation(x, y, z));
            if (rotations != null) for (AxisRotation rotation: rotations) rotation.apply();
            window.transform(scale(sx, sy, sz));
            drawShapes(depthTest, false, null);
        }
        finally { window.removeMatrix(); }
    }

    @Override
    public void draw(double x, double y, double z, double sx, double sy, double sz,
                     double yaw, double pitch, double roll, boolean depthTest)
    {
        window.addMatrix();
        try
        {
            window.transform(translation(x, y, z));
            Rotation.transform(window, -pitch, -roll, -yaw);
            window.transform(scale(sx, sy, sz));
            drawShapes(depthTest, false, null);
        }
        finally { window.removeMatrix(); }
    }

    @Override public void draw(double x, double y, double sx, double sy, double yaw)
    {
        window.addMatrix();
        try
        {
            window.transform(translation(x, y, 0));
            Rotation.transform(window, 0, 0, -yaw);
            window.transform(scale(sx, sy, 0));
            drawShapes(false, true, null);
        }
        finally { window.removeMatrix(); }
    }

    @Override public void draw2D(double x, double y, double z, double sx, double sy, double sz)
    {
        window.addMatrix();
        try
        {
            window.transform(translation(x, y, z));
            window.transform(scale(1, 1, 0));
            Rotation.transform(window, 0, Math.PI * -3 / 4, 0);
            Rotation.transform(window, 0, 0, Math.PI / 4);
            window.transform(scale(sx, sy, sz));
            drawShapes(false, false, null);
        }
        finally { window.removeMatrix(); }
    }

    void drawShapes(boolean depth, boolean flat, PosedModel pose)
    {
        if (shapes == null) return;
        String texture = material == null ? null : material.texture;
        if (texture != null && parent != null)
        {
            String skin = parent.currentSkin.get(texture);
            if (skin == null) skin = parent.currentSkin.get("<texture>");
            if (skin != null) texture = skin;
        }
        boolean depthWrite = material == null || (material.useDefaultDepthMask ? window.colorA >= 1 : material.depthMask);
        int first = window.drawing.vertexCount();
        float[] vertexColor = new float[4];
        for (Shape shape: shapes)
        {
            if (shape == null || shape.points == null) continue;
            int[] order = shape.points.length == 4 ? new int[] {0, 1, 2, 0, 2, 3} : null;
            int count = order == null ? shape.points.length : order.length;
            for (int vi = 0; vi < count; vi++)
            {
                int i = order == null ? vi : order[vi];
                Point p = shape.points[i];
                if (p == null) continue;
                Point uv = shape.texCoords != null && i < shape.texCoords.length ? shape.texCoords[i] : null;
                double[] shapeVertexColor = shape.colors != null && i < shape.colors.length ? shape.colors[i] : p.color;
                double r = window.colorR * shape.brightness;
                double g = window.colorG * shape.brightness;
                double b = window.colorB * shape.brightness;
                double a = window.colorA;
                if (material != null)
                {
                    r *= material.colorR;
                    g *= material.colorG;
                    b *= material.colorB;
                    a *= material.colorA;
                }
                if (shapeVertexColor != null && shapeVertexColor.length >= 4)
                {
                    r *= shapeVertexColor[0];
                    g *= shapeVertexColor[1];
                    b *= shapeVertexColor[2];
                    a *= shapeVertexColor[3];
                }
                vertexColor[0] = colorUnit(r);
                vertexColor[1] = colorUnit(g);
                vertexColor[2] = colorUnit(b);
                vertexColor[3] = colorUnit(a);
                double[] posed = pose instanceof VulkanPosedModel ? ((VulkanPosedModel) pose).transformPoint(p) : null;
                window.drawing.vertex(posed == null ? p.x : posed[0], posed == null ? p.y : posed[1],
                        flat ? 0 : (posed == null ? p.z : posed[2]), uv == null ? 0 : uv.x, uv == null ? 0 : uv.y, vertexColor);
            }
        }
        window.drawing.finish(first, texture, depth, depthWrite, (material != null && material.glow) || window.forceModelGlow);
    }

    private static double[] translation(double x, double y, double z)
    { return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1}; }

    private static double[] scale(double x, double y, double z)
    { return new double[] {x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1}; }

    private static float colorUnit(double normalized)
    {
        if (Double.isNaN(normalized)) return 0;
        return (float) Math.max(0, Math.min(1, normalized));
    }
}
