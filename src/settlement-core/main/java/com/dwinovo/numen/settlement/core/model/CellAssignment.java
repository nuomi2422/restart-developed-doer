package com.dwinovo.numen.settlement.core.model;

/** 某个网格单元登记给哪个设施（{@code facilityId} 为空表示空格）。 */
public record CellAssignment(CellKey cell, String facilityId) {

    public CellAssignment {
        if (cell == null) throw new IllegalArgumentException("cell required");
    }

    public static CellAssignment of(CellKey cell, String facilityId) {
        return new CellAssignment(cell, facilityId);
    }
}
