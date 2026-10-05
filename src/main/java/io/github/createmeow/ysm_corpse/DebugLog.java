package io.github.createmeow.ysm_corpse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 调试日志开关。
 * <p>
 * 默认关闭。在 {@code config/ysm_corpse.json} 中写入
 * {@code {"debug_logging": true}} 即可打开本模组的全部调试输出；
 * 删除该键或解析失败时一律关闭。读取结果缓存 10 秒，避免高频日志点重复读文件。
 * <p>
 * 仅被日志输出点引用，不参与任何兼容逻辑。
 */
public final class DebugLog {
    private static volatile boolean enabled;
    private static volatile long checkedAt;
    private static final long TTL_MS = 10_000L;

    private DebugLog() {
    }

    public static boolean isEnabled() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < TTL_MS) {
            return enabled;
        }
        enabled = read();
        checkedAt = now;
        return enabled;
    }

    private static boolean read() {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("ysm_corpse.json");
            if (!Files.isRegularFile(path)) {
                return false;
            }
            JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            return json.has("debug_logging") && json.get("debug_logging").getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }
}
