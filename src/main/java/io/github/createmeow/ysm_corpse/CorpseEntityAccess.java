package io.github.createmeow.ysm_corpse;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过反射访问 Corpse 模组（{@code de.maxhenkel.corpse}）的尸体实体，
 * 避免编译期依赖：Corpse 缺席时所有查询安全返回默认值。
 */
public final class CorpseEntityAccess {
    public static final String MOD_ID = "corpse";
    private static final String CORPSE_ENTITY_CLASS = "de.maxhenkel.corpse.entities.CorpseEntity";
    private static final String CORPSE_MAIN_CLASS = "de.maxhenkel.corpse.Main";

    private CorpseEntityAccess() {
    }

    public static boolean isCorpse(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName(CORPSE_ENTITY_CLASS, false, entity.getClass().getClassLoader());
            return type.isInstance(entity);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** 尸体对应死亡玩家的 UUID。 */
    public static Optional<UUID> playerUuid(Entity corpse) {
        Object value = invoke(corpse, "getCorpseUUID");
        if (value instanceof Optional<?> optional && optional.orElse(null) instanceof UUID uuid) {
            return Optional.of(uuid);
        }
        return Optional.empty();
    }

    /** 尸体显示的玩家名。 */
    public static String playerName(Entity corpse) {
        if (invoke(corpse, "getCorpseName") instanceof String name) {
            return name;
        }
        return "";
    }

    /** 尸体是否是骷髅形态（原玩家皮肤不可用时）。 */
    public static boolean isSkeleton(Entity corpse) {
        return invoke(corpse, "isSkeleton") instanceof Boolean flag && flag;
    }

    /** Corpse 记录的玩家模型序号（可作为皮肤部件信息的兜底来源）。 */
    public static byte modelIndex(Entity corpse) {
        if (invoke(corpse, "getCorpseModel") instanceof Byte b) {
            return b;
        }
        return 0;
    }

    /** 尸体保存的装备（含盔甲），按下标对应 EquipmentSlot。 */
    public static NonNullList<ItemStack> equipment(Entity corpse) {
        if (invoke(corpse, "getEquipment") instanceof NonNullList<?> list) {
            @SuppressWarnings("unchecked")
            NonNullList<ItemStack> equipment = (NonNullList<ItemStack>) list;
            return equipment;
        }
        return NonNullList.withSize(6, ItemStack.EMPTY);
    }

    /** 反射获取 Corpse 注册的实体类型（用于客户端渲染器覆盖）。 */
    public static EntityTypeRef corpseEntityType() {
        try {
            Class<?> main = Class.forName(CORPSE_MAIN_CLASS, false, CorpseEntityAccess.class.getClassLoader());
            Object resolved = resolveEntityType(main.getField("CORPSE_ENTITY_TYPE").get(null));
            if (resolved instanceof EntityType<?> type) {
                return new EntityTypeRef(type);
            }
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            // Corpse 未安装
        }
        return null;
    }

    /** 读取 Corpse 服务端配置 spawnCorpseOnFace（尸体是否趴在地上）。 */
    public static boolean lieOnFace() {
        try {
            Class<?> main = Class.forName(CORPSE_MAIN_CLASS, false, CorpseEntityAccess.class.getClassLoader());
            Object config = main.getField("SERVER_CONFIG").get(null);
            Object spec = findField(config, "spawnCorpseOnFace");
            if (spec != null && spec.getClass().getMethod("get").invoke(spec) instanceof Boolean flag) {
                return flag;
            }
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            // 配置不可读时按默认（仰卧）处理
        }
        return false;
    }

    private static Object findField(Object target, String fieldName) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** DeferredHolder / RegistryObject 兼容解析：优先 value() 再 get()。 */
    private static Object resolveEntityType(Object holder) {
        for (String getter : new String[]{"value", "get"}) {
            try {
                return holder.getClass().getMethod(getter).invoke(holder);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** 对 EntityType 的弱引用包装，仅用于类型判断与渲染器覆盖。 */
    public record EntityTypeRef(EntityType<?> type) {
    }
}
