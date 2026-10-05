package io.github.createmeow.ysm_corpse.client;

import net.neoforged.fml.ModList;

/**
 * 客户端兼容状态查询。YSM 的 mod id 为 yes_steve_model，
 * 兼容旧版本可能使用的 ysm。
 */
public final class ClientCompat {
    private ClientCompat() {
    }

    public static boolean isYsmPresent() {
        return ModList.get().isLoaded("yes_steve_model") || ModList.get().isLoaded("ysm");
    }
}
