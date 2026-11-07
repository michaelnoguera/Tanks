package vulkanwindow;

import basewindow.Model;
import basewindow.ModelPart;
import basewindow.PosedModel;
import basewindow.transformation.AxisRotation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Pose-aware model facade. Bone animation data is retained; geometry is recorded per part. */
public class VulkanPosedModel extends PosedModel
{
    public VulkanPosedModel(Model model) { super(model); }

    @Override public void setSkin(HashMap<String, String> skins) { model.setSkin(skins); }

    @Override public void setSkin(String tex) { model.setSkin(tex); }

    @Override
    public void draw(double x, double y, double z, double sx, double sy, double sz,
                     AxisRotation[] rotations, boolean depthTest)
    {
        compilePose();
        VulkanGameWindow window = (VulkanGameWindow) model.window;
        window.addMatrix();
        window.transform(translation(x, y, z));
        if (rotations != null) for (AxisRotation rotation: rotations) rotation.apply();
        window.transform(scale(sx, sy, sz));
        drawParts(depthTest, false);
        window.removeMatrix();
    }

    @Override
    public void draw(double x, double y, double z, double sx, double sy, double sz,
                     double yaw, double pitch, double roll, boolean depthTest)
    {
        compilePose();
        VulkanGameWindow window = (VulkanGameWindow) model.window;
        window.addMatrix();
        window.transform(translation(x, y, z));
        window.transform(rotation(yaw, pitch, roll));
        window.transform(scale(sx, sy, sz));
        drawParts(depthTest, false);
        window.removeMatrix();
    }

    @Override public void draw(double x, double y, double sx, double sy, double yaw)
    {
        compilePose();
        VulkanGameWindow window = (VulkanGameWindow) model.window;
        window.addMatrix();
        window.transform(translation(x, y, 0));
        window.transform(rotation(yaw, 0, 0));
        window.transform(scale(sx, sy, 0));
        drawParts(false, true);
        window.removeMatrix();
    }

    @Override public void draw2D(double x, double y, double z, double sx, double sy, double sz)
    {
        compilePose();
        VulkanGameWindow window = (VulkanGameWindow) model.window;
        window.addMatrix();
        window.transform(translation(x, y, z));
        window.transform(scale(sx, sy, sz));
        drawParts(false, false);
        window.removeMatrix();
    }

    private void compilePose()
    {
        for (PoseBone bone: bones)
            bone.computeMatrix();

        Set<PoseBone> compiled = new HashSet<>();
        for (PoseBone bone: bones)
            compileBone(bone, compiled);
    }

    private void compileBone(PoseBone bone, Set<PoseBone> compiled)
    {
        if (bone == null || !compiled.add(bone))
            return;

        if (bone.bone.parent != null)
            compileBone(boneMap.get(bone.bone.parent), compiled);

        bone.compileMatrix();
    }

    private void drawParts(boolean depth, boolean flat)
    {
        for (ModelPart part: model.models)
            if (part instanceof VulkanModelPart)
                ((VulkanModelPart) part).drawShapes(depth, flat, this);
            else
                part.draw(0, 0, 0, 1, 1, 1, 0, 0, 0, depth);
    }

    double[] transformPoint(ModelPart.Point point)
    {
        if (point.bones == null || point.boneWeights == null)
            return new double[] {point.x, point.y, point.z};
        double[] result = new double[3];
        double weightSum = 0;
        int n = Math.min(point.bones.length, point.boneWeights.length);
        for (int i = 0; i < n; i++)
        {
            double weight = point.boneWeights[i];
            if (weight <= 0 || point.bones[i] == null) continue;
            PoseBone pose = boneMap.get(point.bones[i]);
            if (pose == null) continue;
            double[] m = pose.compiledMatrix;
            result[0] += (m[0] * point.x + m[4] * point.y + m[8] * point.z + m[12]) * weight;
            result[1] += (m[1] * point.x + m[5] * point.y + m[9] * point.z + m[13]) * weight;
            result[2] += (m[2] * point.x + m[6] * point.y + m[10] * point.z + m[14]) * weight;
            weightSum += weight;
        }
        if (weightSum <= 0) return new double[] {point.x, point.y, point.z};
        if (Math.abs(weightSum - 1) > 1e-8)
            for (int i = 0; i < 3; i++) result[i] /= weightSum;
        return result;
    }

    private static double[] translation(double x, double y, double z)
    {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1};
    }

    private static double[] scale(double x, double y, double z)
    {
        return new double[] {x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1};
    }

    private static double[] rotation(double yaw, double pitch, double roll)
    {
        double cy = Math.cos(-yaw);
        double sy = Math.sin(-yaw);
        double cp = Math.cos(-pitch);
        double sp = Math.sin(-pitch);
        double cr = Math.cos(-roll);
        double sr = Math.sin(-roll);
        return new double[]
        {
            cy * cr - sy * sp * sr, -sy * cp, cy * sr + sy * sp * cr, 0,
            sy * cr + cy * sp * sr, cy * cp, sy * sr - cy * sp * cr, 0,
            -cp * sr, sp, cp * cr, 0,
            0, 0, 0, 1
        };
    }
}
