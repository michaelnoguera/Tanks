package basewindow;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stores the game shader parameters used by the Vulkan draw adapters on the CPU. */
public final class VulkanShaderUtil extends BaseShaderUtil
{
    private final ShaderProgram program;

    public VulkanShaderUtil(ShaderProgram program)
    {
        this.program = program;
    }

    @Override
    public void setUp(String vert, String[] vertHeaders, String frag, String[] fragHeaders) throws Exception
    {
        setUp(vert, vertHeaders, null, null, frag, fragHeaders);
    }

    @Override
    public void setUp(String vert, String[] vertHeaders, String geom, String[] geomHeaders, String frag, String[] fragHeaders)
            throws Exception
    {
        program.bindAttributes();
        program.initializeAttributeParameters();
        setUpUniforms();
    }

    @Override
    public void setUpUniforms() throws InstantiationException, IllegalAccessException
    {
        for (Field field: ShaderProgram.getFields(program.getClass()))
        {
            if (!Modifier.isStatic(field.getModifiers()) && ShaderProgram.IUniform.class.isAssignableFrom(field.getType()))
                field.set(program, createUniform(field.getType()));
        }

        ShaderGroup group = program.group;
        for (Field field: ShaderProgram.getFields(group.getClass()))
        {
            Class<?> type = field.getType();
            if (Modifier.isStatic(field.getModifiers()) || !ShaderGroup.IGroupUniform.class.isAssignableFrom(type))
                continue;

            ShaderGroup.IGroupUniform uniform = (ShaderGroup.IGroupUniform) field.get(group);
            if (uniform == null)
            {
                uniform = (ShaderGroup.IGroupUniform) type.newInstance();
                uniform.initialize(program.window, group.stages.size());
                field.set(group, uniform);
            }

            // The uniform's concrete base type names the matching ShaderProgram uniform interface.
            ParameterizedType baseType = (ParameterizedType) type.getGenericSuperclass();
            int stage = program.stageIndex;
            if (uniform instanceof ShaderGroup.GroupPrimitiveUniform)
            {
                Class<?> uniformType = (Class<?>) baseType.getActualTypeArguments()[1];
                ShaderGroup.GroupPrimitiveUniform<?, ?> primitive = (ShaderGroup.GroupPrimitiveUniform<?, ?>) uniform;
                primitive.uniforms[stage] = (ShaderProgram.IPrimitiveUniform<?>) createUniform(uniformType);
            }
            else if (uniform instanceof ShaderGroup.GroupMatrixUniform)
            {
                Class<?> uniformType = (Class<?>) baseType.getActualTypeArguments()[0];
                ShaderGroup.GroupMatrixUniform<?> matrix = (ShaderGroup.GroupMatrixUniform<?>) uniform;
                matrix.uniforms[stage] = (ShaderProgram.IMatrixUniform) createUniform(uniformType);
            }
        }

        program.initializeUniforms();
    }

    @Override
    public ShaderProgram.Attribute getAttribute()
    {
        return new ShaderProgram.Attribute()
        {
            @Override
            public void bind() {}
        };
    }

    @Override
    public void set() {}

    @SuppressWarnings("unchecked")
    public static <T> T get(ShaderGroup.GroupPrimitiveUniform<T, ?> uniform)
    {
        return (T) copyValue(uniform.uniforms[uniform.window.currentShaderStage.index].get());
    }

    /**
     * Returns the value of a named group uniform for the currently selected
     * shader stage. Array values are copied so callers cannot mutate shader state.
     */
    public static Object get(ShaderGroup group, String uniformName)
    {
        if (group == null || group.window == null)
            return null;
        if (group.window.currentShaderStage == null)
            return null;

        return get(group, uniformName, group.window.currentShaderStage.index);
    }

    /** Reads a program-local uniform first, then falls back to a group uniform. */
    public static Object get(ShaderProgram program, String uniformName)
    {
        if (program == null)
            return null;
        Field field = findField(program.getClass(), uniformName);
        if (field != null && ShaderProgram.IUniform.class.isAssignableFrom(field.getType()))
        {
            try
            {
                ShaderProgram.IUniform uniform = (ShaderProgram.IUniform) field.get(program);
                if (uniform instanceof ShaderProgram.IPrimitiveUniform)
                    return copyValue(((ShaderProgram.IPrimitiveUniform<?>) uniform).get());
                if (uniform instanceof ShaderProgram.IMatrixUniform)
                    return copyValue(((ShaderProgram.IMatrixUniform) uniform).getMatrix());
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException("Cannot read shader uniform " + uniformName, e);
            }
        }
        return get(program.group, uniformName);
    }

    /** Returns the value of a named group uniform for a specific stage. */
    public static Object get(ShaderGroup group, String uniformName, int stage)
    {
        Field field = findField(group.getClass(), uniformName);
        if (field == null || !ShaderGroup.IGroupUniform.class.isAssignableFrom(field.getType()))
            return null;

        try
        {
            ShaderGroup.IGroupUniform uniform = (ShaderGroup.IGroupUniform) field.get(group);
            if (uniform == null)
                return null;
            if (uniform instanceof ShaderGroup.GroupPrimitiveUniform)
            {
                ShaderProgram.IPrimitiveUniform<?> value = ((ShaderGroup.GroupPrimitiveUniform<?, ?>) uniform).uniforms[stage];
                return value == null ? null : copyValue(value.get());
            }
            if (uniform instanceof ShaderGroup.GroupMatrixUniform)
            {
                ShaderProgram.IMatrixUniform value = ((ShaderGroup.GroupMatrixUniform<?>) uniform).uniforms[stage];
                return value == null ? null : copyValue(value.getMatrix());
            }
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Cannot read shader group uniform " + uniformName, e);
        }

        return null;
    }

    /** Returns field names for attributes declared by this program or its group. */
    public static List<String> getAttributeNames(ShaderProgram program)
    {
        List<String> names = new ArrayList<>();
        for (Field field: ShaderProgram.getFields(program.getClass()))
        {
            if (ShaderProgram.Attribute.class.isAssignableFrom(field.getType()))
                names.add(field.getName());
        }
        for (Field field: ShaderProgram.getFields(program.group.getClass()))
        {
            if (ShaderGroup.Attribute.class.isAssignableFrom(field.getType()) && !names.contains(field.getName()))
                names.add(field.getName());
        }
        return Collections.unmodifiableList(names);
    }

    private static Field findField(Class<?> type, String name)
    {
        for (Field field: ShaderProgram.getFields(type))
        {
            if (field.getName().equals(name))
                return field;
        }
        return null;
    }

    private static Object copyValue(Object value)
    {
        if (value != null && value.getClass().isArray())
        {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            System.arraycopy(value, 0, copy, 0, length);
            return copy;
        }
        return value;
    }

    private static Object createUniform(Class<?> type)
    {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new UniformValue(type));
    }

    private static final class UniformValue implements InvocationHandler
    {
        private final Class<?> type;
        private final boolean matrix;
        private Object value;
        private boolean transpose;

        UniformValue(Class<?> type)
        {
            this.type = type;
            matrix = ShaderProgram.IMatrixUniform.class.isAssignableFrom(type);
            if (matrix)
                value = new float[0];
            else if (type == ShaderProgram.Uniform1b.class)
                value = false;
            else if (ShaderProgram.Uniform1i.class.isAssignableFrom(type))
                value = 0;
            else if (type == ShaderProgram.Uniform1f.class)
                value = 0f;
            else
                value = newVector(type);
        }

        private static Object newVector(Class<?> type)
        {
            for (Method method: type.getMethods())
            {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("set") && parameters.length > 1)
                    return Array.newInstance(parameters[0], parameters.length);
            }
            throw new IllegalArgumentException("Unsupported uniform " + type.getName());
        }

        private void set(Object[] arguments)
        {
            if (matrix)
            {
                value = ((float[]) arguments[0]).clone();
                transpose = (boolean) arguments[1];
            }
            else if (arguments.length > 1)
            {
                for (int i = 0; i < arguments.length; i++)
                    Array.set(value, i, arguments[i]);
            }
            else if (value.getClass().isArray())
                System.arraycopy(arguments[0], 0, value, 0, Array.getLength(value));
            else
                value = arguments[0];
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments)
        {
            switch (method.getName())
            {
                case "set":
                    set(arguments);
                    return null;
                case "bind":
                    return null;
                case "get":
                case "getMatrix":
                    return value;
                case "getTranspose":
                    return transpose;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == arguments[0];
                case "toString":
                    return type.getSimpleName();
                default:
                    throw new UnsupportedOperationException(method.toString());
            }
        }
    }
}
