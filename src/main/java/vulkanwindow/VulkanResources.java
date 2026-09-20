package vulkanwindow;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

import static org.lwjgl.vulkan.VK11.*;

/** Device resources retained until the renderer has finished all submitted frames. */
final class VulkanResources implements AutoCloseable
{
    final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue queue;
    private final long commandPool;
    long descriptorLayout;
    private long descriptorPool;
    private long sampler;
    private final Map<String, Texture> textures = new LinkedHashMap<>();

    VulkanResources(VkDevice device, VkPhysicalDevice physicalDevice, VkQueue queue, long commandPool)
    {
        this.device = device;
        this.physicalDevice = physicalDevice;
        this.queue = queue;
        this.commandPool = commandPool;
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(binding), null, handle), "create texture descriptor layout");
            descriptorLayout = handle.get(0);
            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
            size.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1024).pPoolSizes(size), null, handle), "create texture descriptor pool");
            descriptorPool = handle.get(0);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, handle), "create texture sampler");
            sampler = handle.get(0);
        }
        catch (RuntimeException | Error failure)
        {
            close();
            throw failure;
        }
    }

    private int memoryType(int bits, int flags, MemoryStack stack)
    {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
        for (int i = 0; i < properties.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & flags) == flags) return i;
        throw new IllegalStateException("No memory type for flags " + flags);
    }

    Buffer buffer(long size, int usage)
    {
        Buffer result = new Buffer();
        result.size = size;
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "create buffer");
            result.handle = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, result.handle, requirements);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack)), null, handle), "allocate buffer memory");
            result.memory = handle.get(0);
            check(vkBindBufferMemory(device, result.handle, result.memory, 0), "bind buffer memory");
            return result;
        }
        catch (RuntimeException | Error failure)
        {
            result.close();
            throw failure;
        }
    }

    Image image(int width, int height, int format, int usage, int aspect)
    {
        Image result = new Image();
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            LongBuffer handle = stack.mallocLong(1);
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                    .format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            check(vkCreateImage(device, info, null, handle), "create image");
            result.handle = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, result.handle, requirements);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack)), null, handle), "allocate image memory");
            result.memory = handle.get(0);
            check(vkBindImageMemory(device, result.handle, result.memory, 0), "bind image memory");
            VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(result.handle)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            view.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, view, null, handle), "create resource image view");
            result.view = handle.get(0);
            return result;
        }
        catch (RuntimeException | Error failure)
        {
            result.close();
            throw failure;
        }
    }

    long texture(String resource)
    {
        Texture cached = textures.get(resource);
        if (cached != null) return cached.descriptor;
        BufferedImage pixels;
        if (resource == null)
        {
            pixels = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            pixels.setRGB(0, 0, 0xffffffff);
        }
        else
        {
            String path = resource.startsWith("/") ? resource : "/" + resource;
            try (InputStream input = VulkanResources.class.getResourceAsStream(path))
            {
                if (input == null) throw new IllegalArgumentException("Missing texture " + path);
                pixels = ImageIO.read(input);
                if (pixels == null) throw new IllegalArgumentException("Unsupported texture " + path);
            }
            catch (IOException failure) { throw new IllegalStateException("Cannot read texture " + path, failure); }
        }
        Texture texture = new Texture();
        long bytesRequired = Math.multiplyExact(Math.multiplyExact((long) pixels.getWidth(), pixels.getHeight()), 4);
        try (MemoryStack stack = MemoryStack.stackPush(); Buffer staging = buffer(bytesRequired, VK_BUFFER_USAGE_TRANSFER_SRC_BIT))
        {
            ByteBuffer bytes = staging.map(stack);
            try
            {
                for (int y = 0; y < pixels.getHeight(); y++)
                    for (int x = 0; x < pixels.getWidth(); x++)
                    {
                        int rgba = pixels.getRGB(x, y);
                        bytes.put((byte) (rgba >> 16)).put((byte) (rgba >> 8)).put((byte) rgba).put((byte) (rgba >> 24));
                    }
            }
            finally { staging.unmap(); }
            texture.image = image(pixels.getWidth(), pixels.getHeight(), VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            upload(stack, staging, texture.image, pixels.getWidth(), pixels.getHeight());
            LongBuffer handle = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "allocate texture descriptor");
            texture.descriptor = handle.get(0);
            VkDescriptorImageInfo.Buffer sampled = VkDescriptorImageInfo.calloc(1, stack);
            sampled.get(0).sampler(sampler).imageView(texture.image.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(texture.descriptor).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(sampled);
            vkUpdateDescriptorSets(device, write, null);
            textures.put(resource, texture);
            return texture.descriptor;
        }
        catch (RuntimeException | Error failure)
        {
            if (texture.image != null) texture.image.close();
            throw failure;
        }
    }

    private void upload(MemoryStack stack, Buffer staging, Image target, int width, int height)
    {
        PointerBuffer pointer = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "allocate upload commands");
        VkCommandBuffer command = new VkCommandBuffer(pointer.get(0), device);
        try
        {
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin texture upload");
            barrier(stack, command, target.handle, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(command, staging.handle, target.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            barrier(stack, command, target.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            check(vkEndCommandBuffer(command), "end texture upload");
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command.address())), 0), "submit texture upload");
            check(vkQueueWaitIdle(queue), "wait texture upload");
        }
        finally { vkFreeCommandBuffers(device, commandPool, command); }
    }

    static void barrier(MemoryStack stack, VkCommandBuffer command, long image, int oldLayout, int newLayout,
                        int sourceAccess, int destinationAccess, int sourceStage, int destinationStage)
    {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, null, barrier);
    }

    final class Buffer implements AutoCloseable
    {
        long handle;
        long memory;
        long size;

        ByteBuffer map(MemoryStack stack)
        {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, size, 0, pointer), "map buffer");
            return org.lwjgl.system.MemoryUtil.memByteBuffer(pointer.get(0), Math.toIntExact(size));
        }

        void unmap() { vkUnmapMemory(device, memory); }

        @Override public void close()
        {
            if (handle != 0) vkDestroyBuffer(device, handle, null);
            if (memory != 0) vkFreeMemory(device, memory, null);
            handle = 0;
            memory = 0;
        }
    }

    final class Image implements AutoCloseable
    {
        long handle;
        long memory;
        long view;

        @Override public void close()
        {
            if (view != 0) vkDestroyImageView(device, view, null);
            if (handle != 0) vkDestroyImage(device, handle, null);
            if (memory != 0) vkFreeMemory(device, memory, null);
            view = 0;
            handle = 0;
            memory = 0;
        }
    }

    private static final class Texture
    {
        Image image;
        long descriptor;
    }

    @Override public void close()
    {
        if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
        descriptorPool = 0;
        for (Texture texture: textures.values()) texture.image.close();
        textures.clear();
        if (sampler != 0) vkDestroySampler(device, sampler, null);
        sampler = 0;
        if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
        descriptorLayout = 0;
    }

    private static void check(int result, String operation)
    {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed (VkResult " + result + ")");
    }
}
