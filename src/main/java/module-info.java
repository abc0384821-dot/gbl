/** 启动器模块声明。JavaFX 必须走 module-path，classpath 启动会报「缺少 JavaFX 运行时组件」。 */
module com.mclauncher {
    requires javafx.controls;
    // 系统托盘（java.awt.SystemTray）与托盘图标那枚现画的位图都在 java.desktop
    requires java.desktop;
    // 官方版本清单走 HttpClient，JDK 自带的这个模块（launchermeta.mojang.com 直连）
    requires java.net.http;
    // 0.9.6 r3：开机自启（BootAutostart）改用 C:\Windows\System32\reg.exe 直接操作真实 Run 键，
    // 不再需要偏好模块（旧实现的偏好 API 只落到 Java 偏好树的假子键，写不到登录时读取的 Run 键）

    exports com.mclauncher;
    exports com.mclauncher.ui;
}
