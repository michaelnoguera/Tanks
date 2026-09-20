package vulkanwindow;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/** GPU integration checks; run explicitly on a Vulkan surface with validation enabled. */
public final class VulkanGeometryTest
{
    private VulkanGeometryTest() { }

    public static void main(String[] args) throws Exception
    {
        Path image = Files.createTempFile("tanks-vulkan-geometry-", ".png");
        try (VulkanWindow window = new VulkanWindow(320, 240, "Tanks geometry checks"))
        {
            VulkanRenderer renderer = new VulkanRenderer(window, true, false);
            try
            {
                VulkanDrawList drawing = new VulkanDrawList();
                // A far surface submitted second must not overwrite a nearer surface.
                drawing.setColor(255, 0, 0, 255);
                drawing.fillRect(-1, -1, 0.2, 2, 2, true);
                drawing.setColor(0, 0, 255, 255);
                drawing.fillRect(-1, -1, 0.8, 2, 2, true);
                capture(renderer, window, drawing, image);
                pixel(image, 0.5, 0.5, 255, 0, 0);

                // Disabled depth preserves submission order; alpha blends with previous color.
                drawing.clear();
                drawing.setColor(0, 0, 255, 255);
                drawing.fillRect(-1, -1, 0.1, 2, 2, true);
                drawing.setColor(255, 0, 0, 128);
                drawing.fillRect(-1, -1, 0.9, 2, 2, false);
                capture(renderer, window, drawing, image);
                pixel(image, 0.5, 0.5, 128, 0, 127);

                // Translucent geometry tests depth without occluding a subsequent opaque draw.
                drawing.clear();
                drawing.setColor(255, 0, 0, 128);
                drawing.fillRect(-1, -1, 0.2, 2, 2, true);
                drawing.setColor(0, 255, 0, 255);
                drawing.fillRect(-1, -1, 0.5, 2, 2, true);
                capture(renderer, window, drawing, image);
                pixel(image, 0.5, 0.5, 0, 255, 0);

                // Texture uploads preserve alpha, image orientation, and texel colors.
                drawing.begin();
                drawing.setColor(30, 60, 90, 255);
                drawing.fillRect(-1, -1, 0.5, 2, 2, false);
                drawing.setColor(255, 255, 255, 255);
                drawing.drawImage(-1, -1, 0, 2, 2, 0, 0, 1, 1, "/images/icons/pencil.png", 0, false);
                capture(renderer, window, drawing, image);
                BufferedImage texture = ImageIO.read(VulkanGeometryTest.class.getResourceAsStream("/images/icons/pencil.png"));
                BufferedImage actual = ImageIO.read(image.toFile());
                int compared = 0;
                for (int y = 2; y < texture.getHeight() - 2; y++)
                    for (int x = 2; x < texture.getWidth() - 2; x++)
                    {
                        int expected = texture.getRGB(x, y);
                        boolean uniform = true;
                        for (int dy = -1; dy <= 1; dy++)
                            for (int dx = -1; dx <= 1; dx++)
                                if (expected != texture.getRGB(x + dx, y + dy)) uniform = false;
                        if (!uniform) continue;
                        int alpha = expected >>> 24;
                        if (alpha != 0 && alpha != 255) continue;
                        int sample = actual.getRGB((int) ((x + 0.5) * actual.getWidth() / texture.getWidth()),
                                (int) ((y + 0.5) * actual.getHeight() / texture.getHeight()));
                        int rgb = alpha == 0 ? 0x1e3c5a : expected;
                        compare(sample, rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255);
                        compared++;
                    }
                if (compared < 20) throw new AssertionError("Insufficient texture samples: " + compared);

                // Images match Tanks: they test depth but never write it, even with opaque tint.
                drawing.begin();
                drawing.drawImage(-1, -1, 0.2, 2, 2, 0, 0, 1, 1, "/images/icons/pencil.png", 0, true);
                drawing.setColor(0, 0, 255, 255);
                drawing.fillRect(-1, -1, 0.8, 2, 2, true);
                capture(renderer, window, drawing, image);
                actual = ImageIO.read(image.toFile());
                for (int y = 0; y < actual.getHeight(); y++)
                    for (int x = 0; x < actual.getWidth(); x++) compare(actual.getRGB(x, y), 0, 0, 255);

                // Grow and replace both frame-owned buffers while earlier submissions can be in flight.
                renderer.setDrawing(drawing);
                for (int frame = 0; frame < 12; frame++)
                {
                    drawing.begin();
                    drawing.setColor(frame % 2 == 0 ? 255 : 0, 0, frame % 2 == 0 ? 0 : 255, 255);
                    for (int i = 0; i < 200 + frame * 20; i++) drawing.fillRect(-1, -1, 0.5, 2, 2, false);
                    while (!renderer.drawFrame()) window.pollEvents();
                }
                window.resize(400, 300);
                window.pollEvents();
                capture(renderer, window, drawing, image);
                pixel(image, 0.5, 0.5, 0, 0, 255);
                renderer.waitIdle();
            }
            finally { renderer.close(); }
            if (renderer.validationErrors() != 0) throw new AssertionError("Vulkan validation errors: " + renderer.validationErrors());
        }
        finally { Files.deleteIfExists(image); }
        System.out.println("Vulkan geometry, texture, depth, alpha, buffer growth and resize checks passed");
    }

    private static void capture(VulkanRenderer renderer, VulkanWindow window, VulkanDrawList drawing, Path path)
    {
        renderer.setDrawing(drawing);
        renderer.capture(path);
        int attempts = 0;
        while (!renderer.drawFrame())
        {
            if (++attempts > 100) throw new AssertionError("Could not acquire a frame");
            window.pollEvents();
        }
    }

    private static void pixel(Path path, double x, double y, int r, int g, int b) throws Exception
    {
        BufferedImage image = ImageIO.read(path.toFile());
        compare(image.getRGB((int) (x * image.getWidth()), (int) (y * image.getHeight())), r, g, b);
    }

    private static void compare(int actual, int r, int g, int b)
    {
        if (Math.abs((actual >> 16 & 255) - r) > 2 || Math.abs((actual >> 8 & 255) - g) > 2 || Math.abs((actual & 255) - b) > 2)
            throw new AssertionError("Expected RGB " + r + "," + g + "," + b + " but got " + Integer.toHexString(actual));
    }
}
