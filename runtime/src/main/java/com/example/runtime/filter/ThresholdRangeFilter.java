package com.example.runtime.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Accepts log events whose level falls within [minLevel, maxLevel].
 *
 * Like CorrelationIdLayout, this class lives in runtime.jar (Runtime CL)
 * but is referenced by FQN in logback.xml (loaded by SDK CL logback).
 * Same TCCL fix applies.
 */
public class ThresholdRangeFilter extends Filter<ILoggingEvent> {

    private Level minLevel = Level.TRACE;
    private Level maxLevel = Level.ERROR;

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!isStarted()) return FilterReply.NEUTRAL;
        int level = event.getLevel().toInt();
        if (level >= minLevel.toInt() && level <= maxLevel.toInt()) {
            return FilterReply.ACCEPT;
        }
        return FilterReply.DENY;
    }

    public void setMinLevel(String level) { this.minLevel = Level.toLevel(level); }
    public void setMaxLevel(String level) { this.maxLevel = Level.toLevel(level); }
}
