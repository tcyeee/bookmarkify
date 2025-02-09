import { defineStore } from 'pinia'

function storeSetup() {
    const sysEvents: Map<string, Map<string, KeyEvent>> = new Map();  // 全局按键触发 Map<按键代码,Map<方法名称,触发方法信息>>
    const dialogLoginManage: Set<DialogStatus> = new Set();           // global dialog status manage

    /**
     * 获取当前dialog状态
     * 
     * @param dialogName 查询的窗口
     * @returns status
     */
    function checkDialogStatus(dialogName: DialogStatus | string): boolean {
        const status: DialogStatus = DialogStatus[dialogName as keyof typeof DialogStatus]
        return dialogLoginManage.has(status)
    }

    /**
     * 修改当前dialog状态
     * 
     * @param dialogName 查询的窗口
     * @param status 修改的状态
     */
    function updateDialogStatus(dialogName: DialogStatus, status?: boolean): void {
        if (status && status == true) {
            dialogLoginManage.add(dialogName)
        } else {
            dialogLoginManage.delete(dialogName)
        }
    }

    /**
     * 触发键盘事件
     * 
     * @param keyCode 触发按键
     * @param path 当前页面
     */
    function triggerKeyEvent(keyCode: string, path: string) {
        if (!sysEvents.has(keyCode)) return;
        const functions: Map<string, KeyEvent> = sysEvents.get(keyCode) || new Map();
        if (!functions.has(keyCode + path)) return;
        const info: KeyEvent | undefined = functions.get(keyCode + path) || undefined;

        // 触发对应的方法
        info?.triggerFunc.call(null);
    }

    /**
     * 注册键盘事件
     * 
     * @param keyCode 触发条件1: 按键 
     * @param currentPath 触发条件2: 所属页面
     * @param triggerFunc 触发方法
     */
    function registerKeyEvent(keyCode: string, currentPath: string, triggerFunc: Function) {
        const events: Map<string, KeyEvent> = sysEvents.get(keyCode) || new Map();
        const eventName: string = keyCode + currentPath
        events.set(eventName, { currentPath, triggerFunc });
        sysEvents.set(keyCode, events);
    }

    return { registerKeyEvent, triggerKeyEvent, checkDialogStatus, updateDialogStatus }
}

export const sysBaseStore = defineStore('sys', storeSetup, { persist: true })
