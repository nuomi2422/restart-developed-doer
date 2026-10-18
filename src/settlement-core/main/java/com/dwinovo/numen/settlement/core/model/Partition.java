package com.dwinovo.numen.settlement.core.model;

/** 两个相邻网格单元之间那条边的状态：默认有墙；合并（去墙）后为 OPEN。 */
public enum Partition {
    WALL, OPEN
}
