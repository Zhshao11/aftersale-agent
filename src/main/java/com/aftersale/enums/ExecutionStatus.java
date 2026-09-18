package com.aftersale.enums;

/** 执行结果三态：SUCCESS / FAILED（明确失败，可安全重试）/ UNKNOWN（结果未知，禁止盲目重试） */
public enum ExecutionStatus {
    SUCCESS, FAILED, UNKNOWN
}
