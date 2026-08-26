package com.pglens.engine.detect;

import com.pglens.engine.model.Finding;
import java.util.List;

/**
 * One anti-pattern rule (Strategy). Rules are liberal — they flag the pattern from plan + catalog;
 * the decisive cost/benefit gate is HypoPG validation downstream, not the rule.
 */
interface Rule {

  String id();

  List<Finding> evaluate(PlanContext ctx);
}
