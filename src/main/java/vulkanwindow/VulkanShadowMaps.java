package vulkanwindow;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK11.*;

/** Per-swapchain-image sampled depth maps for the scene shadow pass. */
final class VulkanShadowMaps implements AutoCloseable
{
    private static final int DEFAULT_SIZE = 2048;
    private final VulkanResources resources;
    private final int size;
    private final int format;
    private final VulkanResources.Image[] images;
    private final long[] framebuffers;
    private final boolean[] initialized;
    private long renderPass;
    private long descriptorLayout;
    private long descriptorPool;
    private long sampler;
    private long pipelineLayout;
    private long pipeline;
    private long[] descriptors;

    VulkanShadowMaps(VulkanResources resources, int format, int imageCount)
    {
        this(resources, format, imageCount, DEFAULT_SIZE);
    }

    VulkanShadowMaps(VulkanResources resources, int format, int imageCount, int size)
    {
        if (resources == null) throw new NullPointerException("resources");
        if (imageCount <= 0) throw new IllegalArgumentException("Swapchain image count must be positive");
        if (size <= 0) throw new IllegalArgumentException("Shadow map size must be positive");
        this.resources = resources;
        this.format = format;
        this.size = size;
        this.images = new VulkanResources.Image[imageCount];
        this.framebuffers = new long[imageCount];
        this.initialized = new boolean[imageCount];
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            createRenderPass(stack);
            createDescriptorResources(stack);
            createPipeline(stack);
            for (int i = 0; i < imageCount; i++)
            {
                images[i] = resources.image(size, size, format,
                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                        VK_IMAGE_ASPECT_DEPTH_BIT);
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateFramebuffer(resources.device, VkFramebufferCreateInfo.calloc(stack).sType$Default()
                        .renderPass(renderPass).pAttachments(stack.longs(images[i].view)).width(size).height(size)
                        .layers(1), null, handle), "create shadow framebuffer");
                framebuffers[i] = handle.get(0);
                allocateDescriptor(stack, i);
            }
        }
        catch (RuntimeException | Error failure)
        {
            close();
            throw failure;
        }
    }

    long descriptorLayout()
    {
        return descriptorLayout;
    }

    long descriptor(int image)
    {
        checkImage(image);
        return descriptors[image];
    }

    /**
     * Records the depth-only pass and leaves the image in depth-read-only layout.
     * The caller must wait for the fence associated with this swapchain image before
     * reusing it. Texture descriptors are indexed by draw command and supply alpha cutouts.
     */
    void record(MemoryStack stack, VkCommandBuffer command, int image, VulkanResources.Buffer vertexBuffer,
                List<VulkanDrawList.Command> draws, long[] textureDescriptors)
    {
        checkImage(image);
        if (stack == null || command == null || vertexBuffer == null || draws == null || textureDescriptors == null)
            throw new NullPointerException("shadow recording arguments");
        if (textureDescriptors.length < draws.size())
            throw new IllegalArgumentException("Texture descriptor buffer must have one entry per draw command");

        VkImageMemoryBarrier.Buffer toAttachment = VkImageMemoryBarrier.calloc(1, stack);
        toAttachment.get(0).sType$Default().srcAccessMask(initialized[image] ? VK_ACCESS_SHADER_READ_BIT : 0)
                .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                .oldLayout(initialized[image] ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(images[image].handle);
        toAttachment.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(command,
                initialized[image] ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, 0,
                null, null, toAttachment);

        VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
        clear.get(0).depthStencil().depth(1).stencil(0);
        VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass)
                .framebuffer(framebuffers[image]).pClearValues(clear);
        begin.renderArea().extent().set(size, size);
        vkCmdBeginRenderPass(command, begin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).width(size).height(size).minDepth(0).maxDepth(1);
        vkCmdSetViewport(command, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).extent().set(size, size);
        vkCmdSetScissor(command, 0, scissor);
        vkCmdBindVertexBuffers(command, 0, stack.longs(vertexBuffer.handle), stack.longs(0));
        LongBuffer descriptor = stack.mallocLong(1);
        for (int i = 0; i < draws.size(); i++)
        {
            VulkanDrawList.Command draw = draws.get(i);
            if (draw.clearDepth || !draw.depth || !draw.depthWrite || draw.count == 0)
                continue;
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                    descriptor.put(0, textureDescriptors[i]), null);
            vkCmdDraw(command, draw.count, 1, draw.first, 0);
        }
        vkCmdEndRenderPass(command);
        initialized[image] = true;
    }

    private void createRenderPass(MemoryStack stack)
    {
        VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
        attachment.get(0).format(format).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
        VkAttachmentReference.Buffer reference = VkAttachmentReference.calloc(1, stack);
        reference.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
        subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pDepthStencilAttachment(reference.get(0));
        VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);
        dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        dependencies.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                .srcStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        LongBuffer handle = stack.mallocLong(1);
        check(vkCreateRenderPass(resources.device, VkRenderPassCreateInfo.calloc(stack).sType$Default()
                .pAttachments(attachment).pSubpasses(subpass).pDependencies(dependencies), null, handle), "create shadow render pass");
        renderPass = handle.get(0);
    }

    private void createDescriptorResources(MemoryStack stack)
    {
        LongBuffer handle = stack.mallocLong(1);
        VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
        binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        check(vkCreateDescriptorSetLayout(resources.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .pBindings(binding), null, handle), "create shadow descriptor layout");
        descriptorLayout = handle.get(0);

        VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
        poolSize.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(images.length);
        check(vkCreateDescriptorPool(resources.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(images.length).pPoolSizes(poolSize), null, handle), "create shadow descriptor pool");
        descriptorPool = handle.get(0);

        check(vkCreateSampler(resources.device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER).borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                .compareEnable(false).minLod(0).maxLod(0), null, handle), "create shadow sampler");
        sampler = handle.get(0);
        descriptors = new long[images.length];
    }

    private void allocateDescriptor(MemoryStack stack, int image)
    {
        LongBuffer layouts = stack.longs(descriptorLayout);
        LongBuffer set = stack.mallocLong(1);
        check(vkAllocateDescriptorSets(resources.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(descriptorPool).pSetLayouts(layouts), set), "allocate shadow descriptor");
        descriptors[image] = set.get(0);
        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
        imageInfo.get(0).sampler(sampler).imageView(images[image].view)
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
        VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
        write.get(0).sType$Default().dstSet(descriptors[image]).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(imageInfo);
        vkUpdateDescriptorSets(resources.device, write, null);
    }

    private void createPipeline(MemoryStack stack)
    {
        long vertex = shader(stack, "/vulkan/shadow.vert.spv");
        long fragment = 0;
        try
        {
            fragment = shader(stack, "/vulkan/shadow.frag.spv");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
            binding.get(0).binding(0).stride(VulkanDrawList.FLOATS_PER_VERTEX * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(3, stack);
            attributes.get(0).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(4 * Float.BYTES);
            attributes.get(1).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(8 * Float.BYTES);
            attributes.get(2).binding(0).location(3).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(10 * Float.BYTES);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL).minDepthBounds(0).maxDepthBounds(1);
            VkPipelineColorBlendStateCreateInfo color = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreatePipelineLayout(resources.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(resources.descriptorLayout, descriptorLayout)), null, handle), "create shadow pipeline layout");
            pipelineLayout = handle.get(0);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                            .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes))
                    .pInputAssemblyState(assembly).pViewportState(viewportState).pRasterizationState(raster)
                    .pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(color).pDynamicState(dynamic)
                    .layout(pipelineLayout).renderPass(renderPass).subpass(0);
            check(vkCreateGraphicsPipelines(resources.device, 0, info, null, handle), "create shadow pipeline");
            pipeline = handle.get(0);
        }
        finally
        {
            if (fragment != 0) vkDestroyShaderModule(resources.device, fragment, null);
            vkDestroyShaderModule(resources.device, vertex, null);
        }
    }

    private long shader(MemoryStack stack, String path)
    {
        try (InputStream input = VulkanShadowMaps.class.getResourceAsStream(path))
        {
            if (input == null) throw new IllegalStateException("Missing shader " + path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1)
                output.write(chunk, 0, count);
            byte[] bytes = output.toByteArray();
            if ((bytes.length & 3) != 0) throw new IllegalStateException("Invalid SPIR-V size for " + path);
            ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
            try
            {
                code.put(bytes).flip();
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateShaderModule(resources.device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                        .pCode(code), null, handle), "create shader " + path);
                return handle.get(0);
            }
            finally { MemoryUtil.memFree(code); }
        }
        catch (IOException failure) { throw new IllegalStateException("Cannot read shader " + path, failure); }
    }

    private void checkImage(int image)
    {
        if (image < 0 || image >= images.length) throw new IndexOutOfBoundsException("Shadow image " + image);
    }

    private static void check(int result, String operation)
    {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed (VkResult " + result + ")");
    }

    @Override public void close()
    {
        if (pipeline != 0) vkDestroyPipeline(resources.device, pipeline, null);
        pipeline = 0;
        if (pipelineLayout != 0) vkDestroyPipelineLayout(resources.device, pipelineLayout, null);
        pipelineLayout = 0;
        for (int i = 0; i < framebuffers.length; i++)
        {
            if (framebuffers[i] != 0) vkDestroyFramebuffer(resources.device, framebuffers[i], null);
            framebuffers[i] = 0;
        }
        if (descriptorPool != 0) vkDestroyDescriptorPool(resources.device, descriptorPool, null);
        descriptorPool = 0;
        if (sampler != 0) vkDestroySampler(resources.device, sampler, null);
        sampler = 0;
        for (int i = 0; i < images.length; i++)
        {
            if (images[i] != null) images[i].close();
            images[i] = null;
        }
        if (renderPass != 0) vkDestroyRenderPass(resources.device, renderPass, null);
        renderPass = 0;
        if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(resources.device, descriptorLayout, null);
        descriptorLayout = 0;
        descriptors = null;
    }
}
