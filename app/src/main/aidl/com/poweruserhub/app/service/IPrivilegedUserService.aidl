package com.poweruserhub.app.service;

interface IPrivilegedUserService {
    void destroy() = 16777114;
    String[] execute(String command) = 1;
    int getUid() = 2;
    String[] setProtectedService(String packageName, String componentName, boolean enabled) = 3;
    boolean isProtectedService(String packageName, String componentName) = 4;
    String[] getProtectedServices() = 5;
    String[] drainProtectionEvents() = 6;
}
