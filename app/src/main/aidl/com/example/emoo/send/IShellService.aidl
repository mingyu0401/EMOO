// Shizuku UserService 常驻 shell 的跨进程接口。
// 服务端在以 shell（adb）身份运行的 UserService 进程里持有一个常驻 `sh`，
// 所有 input / uiautomator dump 命令都写进这同一个进程，省掉每条命令 fork 的开销。
package com.example.emoo.send;

interface IShellService {
    /**
     * 在常驻 shell 中执行一行命令。
     * 返回 "<stdout>\n__EMOO_RC__<exitCode>"；超时或 shell 不可用时返回 null。
     */
    String exec(String command, long timeoutMs);

    /** 主动销毁并重建常驻 shell（命令超时把 shell 卡死后调用）。 */
    void reset();
}
