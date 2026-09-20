package vulkanwindow;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK11.*;

/** Owns the device and presentation resources. All calls belong to the window thread. */
public final class VulkanRenderer implements AutoCloseable
{
    private final VulkanWindow window;
    private boolean vsync;
    private boolean presentationModeChanged;
    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private VkQueue presentQueue;
    private int graphicsFamily;
    private int presentFamily;
    private long surface;
    private long debugMessenger;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final AtomicInteger validationErrors = new AtomicInteger();
    private long commandPool;
    private static final int FRAMES_IN_FLIGHT = 2;
    private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] acquired = new long[FRAMES_IN_FLIGHT];
    private final long[] frameFences = new long[FRAMES_IN_FLIGHT];
    private int currentFrame;
    private long swapchain;
    private long renderPass;
    private long pipelineLayout;
    private long pipeline;
    private long[] views = new long[0];
    private long[] framebuffers = new long[0];
    private long[] presented = new long[0];
    private int width;
    private int height;
    private int generations;

    public VulkanRenderer(VulkanWindow window, boolean validation)
    {
        this(window, validation, true);
    }

    public VulkanRenderer(VulkanWindow window, boolean validation, boolean vsync)
    {
        this.window = window;
        this.vsync = vsync;
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            createInstance(stack, validation);
            LongBuffer handle = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, window.handle(), null, handle), "create surface");
            surface = handle.get(0);
            selectDevice(stack);
            createDevice(stack);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(graphicsFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "create command pool");
            commandPool = handle.get(0);
            PointerBuffer pointers = stack.mallocPointer(FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES_IN_FLIGHT), pointers), "allocate command buffers");
            for (int frame = 0; frame < FRAMES_IN_FLIGHT; frame++)
            {
                commands[frame] = new VkCommandBuffer(pointers.get(frame), device);
                check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "create acquire semaphore");
                acquired[frame] = handle.get(0);
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "create frame fence");
                frameFences[frame] = handle.get(0);
            }
            recreateSwapchain();
        }
        catch (RuntimeException | Error failure)
        {
            close();
            throw failure;
        }
    }

    private void createInstance(MemoryStack stack, boolean validation)
    {
        if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_1) throw new IllegalStateException("Vulkan 1.1 is required");
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateInstanceExtensionProperties((String) null, count, null), "count instance extensions");
        VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
        check(vkEnumerateInstanceExtensionProperties((String) null, count, properties), "query instance extensions");
        Set<String> available = new HashSet<>();
        for (VkExtensionProperties property: properties) available.add(property.extensionNameString());
        PointerBuffer required = glfwGetRequiredInstanceExtensions();
        if (required == null) throw new IllegalStateException("GLFW cannot provide Vulkan surface extensions");
        List<String> extensions = new ArrayList<>();
        for (int i = 0; i < required.remaining(); i++) extensions.add(required.getStringUTF8(i));
        boolean portability = available.contains("VK_KHR_portability_enumeration");
        if (portability) extensions.add("VK_KHR_portability_enumeration");
        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Tanks")).apiVersion(VK_API_VERSION_1_1))
                .flags(portability ? KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0);
        VkDebugUtilsMessengerCreateInfoEXT debugInfo = null;
        if (validation)
        {
            extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            info.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")));
            debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, type, data, user) ->
            {
                if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) validationErrors.incrementAndGet();
                System.err.println("Vulkan: " + VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                return VK_FALSE;
            });
            debugInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                    .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                    .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                    .pfnUserCallback(debugCallback);
            info.pNext(debugInfo.address());
        }
        PointerBuffer names = stack.mallocPointer(extensions.size());
        for (String extension: extensions) names.put(stack.UTF8(extension));
        names.flip();
        info.ppEnabledExtensionNames(names);
        PointerBuffer pointer = stack.mallocPointer(1);
        check(vkCreateInstance(info, null, pointer), "create instance");
        instance = new VkInstance(pointer.get(0), info);
        if (debugInfo != null)
        {
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateDebugUtilsMessengerEXT(instance, debugInfo, null, handle), "create debug messenger");
            debugMessenger = handle.get(0);
        }
    }

    private Set<String> deviceExtensions(VkPhysicalDevice candidate, MemoryStack stack)
    {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, null), "count device extensions");
        VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, properties), "query device extensions");
        Set<String> extensions = new HashSet<>();
        for (VkExtensionProperties property: properties) extensions.add(property.extensionNameString());
        return extensions;
    }

    private void selectDevice(MemoryStack stack)
    {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "count physical devices");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "query physical devices");
        for (int i = 0; i < devices.limit(); i++)
        {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(candidate, properties);
            if (properties.apiVersion() < VK_API_VERSION_1_1 || !deviceExtensions(candidate, stack).contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
            int graphics = -1;
            int present = -1;
            IntBuffer supported = stack.mallocInt(1);
            for (int family = 0; family < families.limit(); family++)
            {
                if (families.get(family).queueCount() == 0) continue;
                if ((families.get(family).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) graphics = family;
                check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, family, surface, supported), "query presentation queue");
                if (supported.get(0) != 0) present = family;
                if (graphics == family && present == family) break;
            }
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(candidate, surface, count, null), "count surface formats");
            if (graphics < 0 || present < 0 || count.get(0) == 0) continue;
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(candidate, surface, count, null), "count present modes");
            if (count.get(0) == 0) continue;
            physicalDevice = candidate;
            graphicsFamily = graphics;
            presentFamily = present;
            System.out.println("Vulkan device: " + properties.deviceNameString());
            return;
        }
        throw new IllegalStateException("No Vulkan 1.1 device supports this window's graphics and presentation queues");
    }

    private void createDevice(MemoryStack stack)
    {
        VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(graphicsFamily == presentFamily ? 1 : 2, stack);
        queues.get(0).sType$Default().queueFamilyIndex(graphicsFamily).pQueuePriorities(stack.floats(1));
        if (queues.limit() == 2) queues.get(1).sType$Default().queueFamilyIndex(presentFamily).pQueuePriorities(stack.floats(1));
        Set<String> extensions = deviceExtensions(physicalDevice, stack);
        PointerBuffer names = stack.mallocPointer(extensions.contains("VK_KHR_portability_subset") ? 2 : 1);
        names.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
        if (extensions.contains("VK_KHR_portability_subset")) names.put(stack.UTF8("VK_KHR_portability_subset"));
        names.flip();
        VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues).ppEnabledExtensionNames(names);
        PointerBuffer pointer = stack.mallocPointer(1);
        check(vkCreateDevice(physicalDevice, info, null, pointer), "create device");
        device = new VkDevice(pointer.get(0), physicalDevice, info);
        vkGetDeviceQueue(device, graphicsFamily, 0, pointer);
        graphicsQueue = new VkQueue(pointer.get(0), device);
        vkGetDeviceQueue(device, presentFamily, 0, pointer);
        presentQueue = new VkQueue(pointer.get(0), device);
    }

    private boolean recreateSwapchain()
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(window.handle(), w, h);
            if (w.get(0) == 0 || h.get(0) == 0) return false;
            waitIdle();
            destroySwapchain();
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps), "query surface capabilities");
            width = caps.currentExtent().width() == -1 ?
                    Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), w.get(0))) : caps.currentExtent().width();
            height = caps.currentExtent().height() == -1 ?
                    Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), h.get(0))) : caps.currentExtent().height();
            if (width <= 0 || height <= 0) return false;
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0)
                throw new IllegalStateException("Surface images cannot be color attachments");
            IntBuffer count = stack.mallocInt(1);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null), "count formats");
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats), "query formats");
            if (!formats.hasRemaining()) throw new IllegalStateException("Surface has no formats");
            int format = formats.get(0).format();
            int colorSpace = formats.get(0).colorSpace();
            if (format == VK_FORMAT_UNDEFINED) format = VK_FORMAT_B8G8R8A8_UNORM;
            for (VkSurfaceFormatKHR candidate: formats)
            {
                if (candidate.format() == VK_FORMAT_B8G8R8A8_UNORM && candidate.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                {
                    format = candidate.format();
                    colorSpace = candidate.colorSpace();
                    break;
                }
            }
            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());
            int compositeAlpha = Integer.lowestOneBit(caps.supportedCompositeAlpha());
            int presentMode = VK_PRESENT_MODE_FIFO_KHR;
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, null), "count presentation modes");
            IntBuffer modes = stack.mallocInt(count.get(0));
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, modes), "query presentation modes");
            if (!vsync)
            {
                for (int i = 0; i < modes.limit(); i++)
                {
                    if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR)
                    {
                        presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                        break;
                    }
                    if (modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR;
                }
            }
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(surface)
                    .minImageCount(imageCount).imageFormat(format).imageColorSpace(colorSpace).imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT).preTransform(caps.currentTransform())
                    .compositeAlpha(compositeAlpha).presentMode(presentMode).clipped(true);
            info.imageExtent().set(width, height);
            if (graphicsFamily == presentFamily) info.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            else info.imageSharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(graphicsFamily, presentFamily));
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, info, null, handle), "create swapchain");
            swapchain = handle.get(0);
            check(vkGetSwapchainImagesKHR(device, swapchain, count, null), "count swapchain images");
            LongBuffer images = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(device, swapchain, count, images), "get swapchain images");
            views = new long[count.get(0)];
            framebuffers = new long[views.length];
            presented = new long[views.length];
            createRenderPass(stack, format);
            createPipeline(stack);
            for (int i = 0; i < views.length; i++)
            {
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(images.get(i)).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(device, view, null, handle), "create image view");
                views[i] = handle.get(0);
                check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                        .pAttachments(stack.longs(views[i])).width(width).height(height).layers(1), null, handle), "create framebuffer");
                framebuffers[i] = handle.get(0);
                check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "create presentation semaphore");
                presented[i] = handle.get(0);
            }
            window.consumeResize();
            presentationModeChanged = false;
            generations++;
            return true;
        }
    }

    private void createRenderPass(MemoryStack stack, int format)
    {
        VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(1, stack);
        attachments.get(0).format(format).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        VkAttachmentReference.Buffer color = VkAttachmentReference.calloc(1, stack);
        color.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
        subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(color);
        VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(1, stack);
        dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        LongBuffer handle = stack.mallocLong(1);
        check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachments)
                .pSubpasses(subpass).pDependencies(dependencies), null, handle), "create render pass");
        renderPass = handle.get(0);
    }

    private long shader(MemoryStack stack, String resource)
    {
        try (InputStream input = VulkanRenderer.class.getResourceAsStream(resource))
        {
            if (input == null) throw new IllegalStateException("Missing shader: " + resource);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) bytes.write(buffer, 0, size);
            ByteBuffer code = MemoryUtil.memAlloc(bytes.size());
            try
            {
                code.put(bytes.toByteArray()).flip();
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), "create shader " + resource);
                return handle.get(0);
            }
            finally { MemoryUtil.memFree(code); }
        }
        catch (IOException error) { throw new IllegalStateException("Cannot read shader " + resource, error); }
    }

    private void createPipeline(MemoryStack stack)
    {
        long vertex = shader(stack, "/vulkan/triangle.vert.spv");
        long fragment = 0;
        try
        {
            fragment = shader(stack, "/vulkan/triangle.frag.spv");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).width(width).height(height).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent().set(width, height);
            VkPipelineColorBlendAttachmentState.Buffer blending = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blending.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default(), null, handle), "create pipeline layout");
            pipelineLayout = handle.get(0);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().pViewports(viewport).pScissors(scissor))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL)
                            .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blending))
                    .layout(pipelineLayout).renderPass(renderPass).subpass(0);
            handle.put(0, 0);
            int result = vkCreateGraphicsPipelines(device, 0, info, null, handle);
            pipeline = handle.get(0);
            check(result, "create graphics pipeline");
        }
        finally
        {
            if (fragment != 0) vkDestroyShaderModule(device, fragment, null);
            vkDestroyShaderModule(device, vertex, null);
        }
    }

    /** Returns false when the window is minimized or the surface must be recreated. */
    public boolean drawFrame()
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(window.handle(), w, h);
            if (w.get(0) == 0 || h.get(0) == 0)
            {
                glfwWaitEventsTimeout(0.05);
                return false;
            }
            if (window.consumeResize() || presentationModeChanged || swapchain == 0)
                if (!recreateSwapchain()) return false;
            VkCommandBuffer command = commands[currentFrame];
            long frameFence = frameFences[currentFrame];
            long acquireSemaphore = acquired[currentFrame];
            check(vkWaitForFences(device, frameFence, true, -1L), "wait frame fence");
            IntBuffer image = stack.mallocInt(1);
            int result = vkAcquireNextImageKHR(device, swapchain, -1L, acquireSemaphore, 0, image);
            if (result == VK_ERROR_OUT_OF_DATE_KHR)
            {
                recreateSwapchain();
                return false;
            }
            if (result != VK_SUBOPTIMAL_KHR) check(result, "acquire swapchain image");
            boolean recreate = result == VK_SUBOPTIMAL_KHR;
            check(vkResetCommandBuffer(command, 0), "reset command buffer");
            record(stack, command, image.get(0));
            // Reset only after acquire succeeds, so OUT_OF_DATE never leaves an unsignaled frame fence.
            check(vkResetFences(device, frameFence), "reset frame fence");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().waitSemaphoreCount(1).pWaitSemaphores(stack.longs(acquireSemaphore))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)).pCommandBuffers(stack.pointers(command.address()))
                    .pSignalSemaphores(stack.longs(presented[image.get(0)]));
            check(vkQueueSubmit(graphicsQueue, submit, frameFence), "submit frame");
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(presented[image.get(0)]))
                    .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(image);
            result = vkQueuePresentKHR(presentQueue, present);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) recreate = true;
            else check(result, "present frame");
            currentFrame = (currentFrame + 1) % FRAMES_IN_FLIGHT;
            if (recreate) recreateSwapchain();
            return result != VK_ERROR_OUT_OF_DATE_KHR;
        }
    }

    private void record(MemoryStack stack, VkCommandBuffer command, int image)
    {
        check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin frame commands");
        VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
        clear.get(0).color().float32(0, 0.025f).float32(1, 0.035f).float32(2, 0.055f).float32(3, 1);
        VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass)
                .framebuffer(framebuffers[image]).pClearValues(clear);
        begin.renderArea().extent().set(width, height);
        vkCmdBeginRenderPass(command, begin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdDraw(command, 3, 1, 0, 0);
        vkCmdEndRenderPass(command);
        check(vkEndCommandBuffer(command), "end frame commands");
    }

    public void setVsync(boolean enabled)
    {
        if (vsync != enabled)
        {
            vsync = enabled;
            presentationModeChanged = true;
        }
    }

    public int validationErrors() { return validationErrors.get(); }

    public int swapchainGenerations() { return generations; }

    public void waitIdle()
    {
        if (device != null) check(vkDeviceWaitIdle(device), "wait device idle");
    }

    private void destroySwapchain()
    {
        for (long framebuffer: framebuffers) if (framebuffer != 0) vkDestroyFramebuffer(device, framebuffer, null);
        framebuffers = new long[0];
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        pipeline = 0;
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        pipelineLayout = 0;
        if (renderPass != 0) vkDestroyRenderPass(device, renderPass, null);
        renderPass = 0;
        for (long view: views) if (view != 0) vkDestroyImageView(device, view, null);
        views = new long[0];
        for (long semaphore: presented) if (semaphore != 0) vkDestroySemaphore(device, semaphore, null);
        presented = new long[0];
        if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
        swapchain = 0;
    }

    @Override
    public void close()
    {
        if (device != null)
        {
            // Device loss must not prevent host-side destruction of owned objects.
            vkDeviceWaitIdle(device);
            destroySwapchain();
            for (long fence: frameFences) if (fence != 0) vkDestroyFence(device, fence, null);
            for (long semaphore: acquired) if (semaphore != 0) vkDestroySemaphore(device, semaphore, null);
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
        surface = 0;
        if (debugMessenger != 0) vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        debugMessenger = 0;
        if (instance != null) vkDestroyInstance(instance, null);
        instance = null;
        if (debugCallback != null) debugCallback.free();
        debugCallback = null;
    }

    private static void check(int result, String operation)
    {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed (VkResult " + result + ")");
    }
}
