package com.dwinovo.numen.settlement.core.logic;

/**
 * 一次方块编辑（纯数据，不含 Minecraft 类型）。
 *
 * <p>由 {@link PlatformMath} 产出，宿主侧翻译成建造任务的目标格。{@code clear=true} 表示
 * 清空该格（放空气），否则 {@code blockId} 是要放的方块。
 */
public record BlockEdit(int x, int y, int z, String blockId, boolean clear) {

    public BlockEdit {
        if (!clear && (blockId == null || blockId.isBlank())) {
            throw new IllegalArgumentException("a placement edit needs a block id");
        }
    }

    public static BlockEdit place(int x, int y, int z, String blockId) {
        return new BlockEdit(x, y, z, blockId, false);
    }

    public static BlockEdit clear(int x, int y, int z) {
        return new BlockEdit(x, y, z, null, true);
    }
}
