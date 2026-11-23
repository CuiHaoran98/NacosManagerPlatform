package com.nacos.ncg.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiskCalculatorTest {

    private final RiskCalculator calc = new RiskCalculator();

    @Test
    void lowWhenNoFactors() {
        RiskAssessment a = calc.assess(false, false, false, 0, 3, false, false, null);
        assertEquals(RiskLevel.LOW, a.level());
    }

    @Test
    void deleteIsHigh() {
        RiskAssessment a = calc.assess(false, false, true, 0, 3, false, false, null);
        assertEquals(RiskLevel.HIGH, a.level());
    }

    @Test
    void prodAndSensitiveIsHigh() {
        RiskAssessment a = calc.assess(true, true, false, 0, 3, false, false, null);
        assertEquals(RiskLevel.HIGH, a.level());
    }

    @Test
    void mediumHitsAccumulate() {
        // prod + diff 超阈 = 2 hits -> MEDIUM
        RiskAssessment a = calc.assess(true, false, false, 5001, 3, false, false, null);
        assertEquals(RiskLevel.MEDIUM, a.level());
        // prod+sensitive+diff 超阈 = 3 hits -> HIGH
        RiskAssessment b = calc.assess(true, true, false, 5001, 3, false, false, null);
        assertEquals(RiskLevel.HIGH, b.level());
    }

    @Test
    void unknownSubscribersRaisesLevel() {
        RiskAssessment a = calc.assess(false, false, false, 0, null, false, false, null);
        assertEquals(RiskLevel.MEDIUM, a.level());
        assertTrue(a.unknowns().stream().anyMatch(u -> u.contains("subscriber")));
    }

    @Test
    void userRequestedLevelCanOnlyRaise() {
        assertEquals(RiskLevel.HIGH,
                calc.assess(false, false, false, 0, 3, false, false, RiskLevel.HIGH).level());
        assertEquals(RiskLevel.LOW,
                calc.assess(false, false, false, 0, 3, false, false, RiskLevel.LOW).level());
    }
}
