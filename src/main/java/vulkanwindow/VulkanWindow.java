package vulkanwindow;

import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwInitVulkanLoader;

/** Owns GLFW and the shared Vulkan loader; use only from the desktop main thread. */
public final class VulkanWindow implements AutoCloseable
{
    private long handle;
    private boolean glfwInitialized;
    private boolean loaderInitialized;
    private GLFWErrorCallback errorCallback;
    private boolean resized;
    private int windowedX;
    private int windowedY;
    private int windowedWidth;
    private int windowedHeight;

    public VulkanWindow(int width, int height, String title)
    {
        try
        {
            Configuration.VULKAN_EXPLICIT_INIT.set(true);
            VK.create();
            loaderInitialized = true;
            errorCallback = GLFWErrorCallback.createPrint(System.err);
            glfwSetErrorCallback(errorCallback);
            glfwInitVulkanLoader(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
            glfwInitialized = glfwInit();
            if (!glfwInitialized) throw new IllegalStateException("GLFW initialization failed");
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            handle = glfwCreateWindow(width, height, title, 0, 0);
            if (handle == 0) throw new IllegalStateException("Vulkan window creation failed");
            glfwSetFramebufferSizeCallback(handle, (window, w, h) -> resized = true);
        }
        catch (RuntimeException | Error failure)
        {
            close();
            throw failure;
        }
    }

    public long handle() { return handle; }

    public boolean shouldClose() { return glfwWindowShouldClose(handle); }

    public void pollEvents() { glfwPollEvents(); }

    public void resize(int width, int height) { glfwSetWindowSize(handle, width, height); }

    public void waitEvents(double seconds) { glfwWaitEventsTimeout(seconds); }

    public void iconify() { glfwIconifyWindow(handle); }

    public void restore() { glfwRestoreWindow(handle); }

    public void setFullscreen(boolean enabled)
    {
        boolean fullscreen = glfwGetWindowMonitor(handle) != 0;
        if (fullscreen == enabled) return;
        if (enabled)
        {
            long monitor = glfwGetPrimaryMonitor();
            if (monitor == 0) throw new IllegalStateException("No fullscreen monitor is available");
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode == null) throw new IllegalStateException("No fullscreen video mode is available");
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                glfwGetWindowPos(handle, x, y);
                windowedX = x.get(0);
                windowedY = y.get(0);
                glfwGetWindowSize(handle, x, y);
                windowedWidth = x.get(0);
                windowedHeight = y.get(0);
            }
            glfwSetWindowMonitor(handle, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
        }
        else
        {
            glfwSetWindowMonitor(handle, 0, windowedX, windowedY, windowedWidth, windowedHeight, GLFW_DONT_CARE);
        }
        resized = true;
    }

    boolean consumeResize()
    {
        boolean result = resized;
        resized = false;
        return result;
    }

    @Override
    public void close()
    {
        if (handle != 0)
        {
            Callbacks.glfwFreeCallbacks(handle);
            glfwDestroyWindow(handle);
            handle = 0;
        }
        if (glfwInitialized)
        {
            glfwTerminate();
            glfwInitialized = false;
        }
        if (errorCallback != null)
        {
            glfwSetErrorCallback(null);
            errorCallback.free();
            errorCallback = null;
        }
        if (loaderInitialized)
        {
            VK.destroy();
            loaderInitialized = false;
        }
    }
}
