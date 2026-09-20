package vulkanprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import vulkancommon.VulkanExtensions;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static vulkanprobe.ProbeReport.object;

/** Standalone query harness. It does not create a logical device or run game code. */
public final class VulkanProbe
{
    private final Map<String, Object> report = object("schemaVersion", 1,
            "os", System.getProperty("os.name"), "osVersion", System.getProperty("os.version"),
            "architecture", System.getProperty("os.arch"), "javaVersion", System.getProperty("java.version"));
    private VkInstance instance;
    private boolean loaderApi11;
    private long window;
    private long surface;
    private boolean glfwInitialized;
    private GLFWErrorCallback errorCallback;

    public static void main(String[] args) throws Exception
    {
        boolean windowed = false;
        Path output = null;
        for (String arg: args)
        {
            if (arg.equals("--window")) windowed = true;
            else if (arg.startsWith("--output=")) output = Paths.get(arg.substring(9));
            else throw new IllegalArgumentException("Usage: VulkanProbe [--window] [--output=report.json]");
        }
        VulkanProbe probe = new VulkanProbe();
        int exitCode = probe.run(windowed);
        String json = ProbeReport.json(probe.report) + System.lineSeparator();
        if (output != null)
        {
            Path parent = output.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Files.write(output, json.getBytes(StandardCharsets.UTF_8));
        }
        System.out.print(json);
        if (exitCode != 0) System.exit(exitCode);
    }

    private int run(boolean windowed)
    {
        report.put("mode", windowed ? "window" : "headless");
        report.put("presentationTested", false);
        report.put("presentationSupportQueried", false);
        report.put("loaderOverride", Configuration.VULKAN_LIBRARY_NAME.get());
        String stage = "loader";
        try
        {
            Configuration.VULKAN_EXPLICIT_INIT.set(true);
            VK.create();
            int loaderVersion = VK.getInstanceVersionSupported();
            loaderApi11 = loaderVersion >= VK_API_VERSION_1_1;
            report.put("loaderApiVersion", version(loaderVersion));
            stage = "instance";
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                Set<String> extensions = instanceExtensions(stack);
                report.put("instanceExtensions", extensions);
                List<String> enabled = new ArrayList<>();
                if (windowed)
                {
                    stage = "window";
                    errorCallback = GLFWErrorCallback.createPrint(System.err);
                    glfwSetErrorCallback(errorCallback);
                    // All GLFW queries and surfaces use the same loader as LWJGL, including MoltenVK.
                    glfwInitVulkanLoader(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
                    glfwInitialized = glfwInit();
                    if (!glfwInitialized) throw new IllegalStateException("GLFW initialization failed; use headless mode for enumeration");
                    PointerBuffer required = glfwGetRequiredInstanceExtensions();
                    if (required == null) throw new IllegalStateException("GLFW found no Vulkan surface extensions");
                    for (int i = 0; i < required.remaining(); i++) enabled.add(required.getStringUTF8(i));
                    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                    window = glfwCreateWindow(64, 64, "Tanks Vulkan probe", 0, 0);
                    if (window == 0) throw new IllegalStateException("Cannot create GLFW probe window");
                }
                boolean portability = extensions.contains("VK_KHR_portability_enumeration");
                if (portability) enabled.add("VK_KHR_portability_enumeration");
                report.put("enabledInstanceExtensions", enabled);
                PointerBuffer names = stack.mallocPointer(enabled.size());
                for (String name: enabled) names.put(stack.UTF8(name));
                names.flip();
                VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                        .pApplicationName(stack.UTF8("Tanks capability probe"))
                        .apiVersion(loaderVersion >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0);
                VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                        .ppEnabledExtensionNames(names).flags(portability ? KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0);
                PointerBuffer pointer = stack.mallocPointer(1);
                stage = "instance";
                check(vkCreateInstance(info, null, pointer), "vkCreateInstance");
                instance = new VkInstance(pointer.get(0), info);
                if (windowed)
                {
                    stage = "surface";
                    LongBuffer handle = stack.mallocLong(1);
                    check(glfwCreateWindowSurface(instance, window, null, handle), "glfwCreateWindowSurface");
                    surface = handle.get(0);
                }
                stage = "devices";
                IntBuffer count = stack.mallocInt(1);
                check(vkEnumeratePhysicalDevices(instance, count, null), "count devices");
                if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical devices found");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate devices");
                List<Object> results = new ArrayList<>();
                report.put("devices", results);
                boolean candidate = false;
                for (int i = 0; i < count.get(0); i++)
                {
                    Map<String, Object> device = inspect(new VkPhysicalDevice(devices.get(i), instance));
                    results.add(device);
                    candidate |= (Boolean) device.get("candidate");
                }
                report.put("presentationSupportQueried", windowed);
                report.put("status", candidate ? (windowed ? "presentation-candidate" : "headless-candidate") : "unsupported");
                return candidate ? 0 : 2;
            }
        }
        catch (RuntimeException | LinkageError failure)
        {
            report.put("status", "error");
            report.put("errorStage", stage);
            report.put("error", failure.toString());
            return 1;
        }
        finally
        {
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            if (instance != null) vkDestroyInstance(instance, null);
            if (window != 0) glfwDestroyWindow(window);
            if (glfwInitialized) glfwTerminate();
            if (errorCallback != null)
            {
                glfwSetErrorCallback(null);
                errorCallback.free();
            }
            VK.destroy();
        }
    }

    private Map<String, Object> inspect(VkPhysicalDevice device)
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device, properties);
            VkPhysicalDeviceLimits limits = properties.limits();
            Map<String, Object> result = object("name", properties.deviceNameString(), "apiVersion", version(properties.apiVersion()),
                    "driverVersionRaw", Integer.toUnsignedLong(properties.driverVersion()), "vendorId", properties.vendorID(),
                    "deviceId", properties.deviceID(), "deviceType", properties.deviceType());
            result.put("limits", object("maxColorAttachments", limits.maxColorAttachments(), "maxImageDimension2D", limits.maxImageDimension2D(),
                    "maxPushConstantsSize", limits.maxPushConstantsSize(), "maxBoundDescriptorSets", limits.maxBoundDescriptorSets(),
                    "framebufferColorSampleCounts", limits.framebufferColorSampleCounts(), "framebufferDepthSampleCounts", limits.framebufferDepthSampleCounts()));
            IntBuffer count = stack.mallocInt(1);
            Set<String> extensions = VulkanExtensions.names((extensionCount, propertiesBuffer) ->
                    vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, extensionCount, propertiesBuffer));
            result.put("extensions", extensions);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
            VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, count, queues);
            List<Object> queueResults = new ArrayList<>();
            boolean graphics = false;
            boolean present = false;
            for (int i = 0; i < count.get(0); i++)
            {
                boolean hasGraphics = queues.get(i).queueCount() > 0 && (queues.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
                graphics |= hasGraphics;
                Boolean hasPresent = null;
                if (surface != 0)
                {
                    IntBuffer supported = stack.mallocInt(1);
                    check(vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, supported), "surface queue support");
                    hasPresent = supported.get(0) != 0;
                    present |= hasPresent && queues.get(i).queueCount() > 0;
                }
                queueResults.add(object("index", i, "flags", queues.get(i).queueFlags(), "count", queues.get(i).queueCount(),
                        "graphics", hasGraphics, "present", hasPresent));
            }
            result.put("queueFamilies", queueResults);
            List<Object> formats = new ArrayList<>();
            boolean color = false;
            boolean depth = false;
            int[] candidates =
            {
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_D16_UNORM, VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT
            };
            for (int format: candidates)
            {
                boolean isDepth = format >= VK_FORMAT_D16_UNORM;
                VkFormatProperties formatProperties = VkFormatProperties.malloc(stack);
                vkGetPhysicalDeviceFormatProperties(device, format, formatProperties);
                int bits = formatProperties.optimalTilingFeatures();
                int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | (isDepth ? VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT :
                        VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT);
                int usage = VK_IMAGE_USAGE_SAMPLED_BIT | (isDepth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
                VkImageFormatProperties image = VkImageFormatProperties.calloc(stack);
                int status = vkGetPhysicalDeviceImageFormatProperties(device, format, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, usage, 0, image);
                if (status != VK_SUCCESS && status != VK_ERROR_FORMAT_NOT_SUPPORTED) check(status, "image format properties");
                boolean usable = (bits & required) == required && status == VK_SUCCESS;
                if (isDepth) depth |= usable;
                else color |= usable;
                formats.add(object("format", format, "optimalTilingFeatures", bits, "sampledAttachmentUsable", usable,
                        "imageQueryResult", status, "sampleCounts", status == VK_SUCCESS ? image.sampleCounts() : 0));
            }
            result.put("formats", formats);
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(device, features);
            result.put("features", object("independentBlend", features.independentBlend(), "samplerAnisotropy", features.samplerAnisotropy(),
                    "fillModeNonSolid", features.fillModeNonSolid(), "wideLines", features.wideLines()));
            if (extensions.contains("VK_KHR_portability_subset") && instance.getCapabilities().Vulkan11 && properties.apiVersion() >= VK_API_VERSION_1_1)
            {
                VkPhysicalDevicePortabilitySubsetFeaturesKHR portability = VkPhysicalDevicePortabilitySubsetFeaturesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(portability.address());
                vkGetPhysicalDeviceFeatures2(device, features2);
                result.put("portabilityFeatures", object("constantAlphaColorBlendFactors", portability.constantAlphaColorBlendFactors(),
                        "events", portability.events(), "imageViewFormatReinterpretation", portability.imageViewFormatReinterpretation(),
                        "imageViewFormatSwizzle", portability.imageViewFormatSwizzle(), "triangleFans", portability.triangleFans(),
                        "pointPolygons", portability.pointPolygons(), "separateStencilMaskRef", portability.separateStencilMaskRef(),
                        "imageView2DOn3DImage", portability.imageView2DOn3DImage(), "multisampleArrayImage", portability.multisampleArrayImage(),
                        "mutableComparisonSamplers", portability.mutableComparisonSamplers(), "samplerMipLodBias", portability.samplerMipLodBias(),
                        "tessellationIsolines", portability.tessellationIsolines(), "tessellationPointMode", portability.tessellationPointMode(),
                        "shaderSampleRateInterpolationFunctions", portability.shaderSampleRateInterpolationFunctions(),
                        "vertexAttributeAccessBeyondStride", portability.vertexAttributeAccessBeyondStride()));
            }
            result.put("portabilitySubsetRequired", extensions.contains("VK_KHR_portability_subset"));
            List<String> reasons = ProbeReport.rejectionReasons(loaderApi11, properties.apiVersion() >= VK_API_VERSION_1_1, graphics,
                    extensions.contains("VK_KHR_swapchain"), limits.maxColorAttachments(), color, depth);
            if (surface != 0)
            {
                if (!present) reasons.add("No presentation queue for probe surface");
                VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
                check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, capabilities), "surface capabilities");
                result.put("surfaceCapabilities", object("minImageCount", capabilities.minImageCount(), "maxImageCount", capabilities.maxImageCount(),
                        "supportedUsageFlags", capabilities.supportedUsageFlags(), "supportedCompositeAlpha", capabilities.supportedCompositeAlpha(),
                        "supportedTransforms", capabilities.supportedTransforms(), "currentTransform", capabilities.currentTransform()));
                if ((capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0)
                    reasons.add("Surface images cannot be color attachments");
                check(vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null), "count surface formats");
                VkSurfaceFormatKHR.Buffer surfaceFormats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
                check(vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, surfaceFormats), "surface formats");
                List<Object> surfaceResults = new ArrayList<>();
                for (int i = 0; i < count.get(0); i++)
                    surfaceResults.add(object("format", surfaceFormats.get(i).format(), "colorSpace", surfaceFormats.get(i).colorSpace()));
                result.put("surfaceFormats", surfaceResults);
                if (surfaceResults.isEmpty()) reasons.add("No surface formats");
                check(vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null), "count present modes");
                IntBuffer modes = stack.mallocInt(count.get(0));
                check(vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, modes), "present modes");
                List<Integer> modeResults = new ArrayList<>();
                for (int i = 0; i < count.get(0); i++) modeResults.add(modes.get(i));
                result.put("presentModes", modeResults);
                if (modeResults.isEmpty()) reasons.add("No present modes");
            }
            result.put("rejectionReasons", reasons);
            result.put("candidate", reasons.isEmpty());
            return result;
        }
    }

    private static Set<String> instanceExtensions(MemoryStack stack)
    {
        return VulkanExtensions.names((count, properties) ->
                vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties));
    }

    private static String version(int value)
    {
        return VK_VERSION_MAJOR(value) + "." + VK_VERSION_MINOR(value) + "." + VK_VERSION_PATCH(value);
    }

    private static void check(int result, String operation)
    {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " returned VkResult " + result);
    }
}
