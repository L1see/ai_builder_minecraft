package com.example.ollamabuilder.parse;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Готовый к установке план постройки: список блоков с относительными
 * координатами и мировая точка-якорь.
 *
 * @param blocks    блоки плана (после санитизации, клампов и дедупликации)
 * @param anchor    мировая позиция якоря: блок на уровне ног игрока, в 2 блоках перед ним
 * @param truncated {@code true}, если список был обрезан до {@code maxBlocks} из конфига
 */
public record BuildPlan(List<BuildBlock> blocks, BlockPos anchor, boolean truncated) {

    /** Компактный конструктор: гарантируем неизменяемый список и не-null значения. */
    public BuildPlan {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        anchor = anchor == null ? BlockPos.ORIGIN : anchor;
    }

    /** Количество блоков в плане. */
    public int size() {
        return blocks.size();
    }

    /** Пустой ли план. */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * Один блок структуры.
     *
     * @param id реестровый идентификатор блока, например {@code minecraft:oak_planks}
     * @param x  смещение по X относительно якоря (вправо от игрока)
     * @param y  смещение по Y относительно якоря (вверх, никогда не отрицательное)
     * @param z  смещение по Z относительно якоря (вперёд от игрока)
     */
    public record BuildBlock(String id, int x, int y, int z) {

        /** Компактный конструктор: id никогда не бывает null. */
        public BuildBlock {
            id = id == null ? "" : id;
        }

        /** Мировая позиция блока при заданном якоре. */
        public BlockPos toWorld(BlockPos anchor) {
            return anchor.add(x, y, z);
        }
    }
}
