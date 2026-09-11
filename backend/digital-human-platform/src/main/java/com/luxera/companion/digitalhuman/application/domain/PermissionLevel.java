package com.luxera.companion.digitalhuman.application.domain;

/**
 * V10 §14/LAP §14 应用动作权限类型。
 *
 * 三级权限语义:
 * - READ:    观察应用状态(资源读取)
 * - WRITE:   修改应用状态(落子/编辑)
 * - EXECUTE: 触发应用行为(发送/下单/对外操作)
 */
public enum PermissionLevel {
    READ,
    WRITE,
    EXECUTE
}