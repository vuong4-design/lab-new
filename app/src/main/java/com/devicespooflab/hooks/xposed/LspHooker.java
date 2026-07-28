package com.devicespooflab.hooks.xposed;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

// The hooker object handed to lsplant::Hook. LSPlant invokes callback(args)
// in place of the target method; this class drives the Xposed-style
// before/after dispatch and calls the backup (original) method in between.
//
// LSPlant contract:
//   - callback signature must be: public Object callback(Object[] args)
//   - args[0] is the receiver for non-static methods; static methods have no
//     receiver placeholder.
//   - the backup method is stored on this object (set via setBackup after
//     lsplant::Hook returns) so callback() can invoke the original.
//   - LSPlant's backup Method normally mirrors the target argument list, but
//     some ART/LSPlant combinations still require the receiver even when the
//     backup Method reports static. Always prefer the original receiver when
//     invoking a zero-extra-arg backup.
public final class LspHooker {

    private static final String TAG = "DS-Xposed";

    // Copy-on-write callback array: mutations (addCallback/removeCallback)
    // create a new array under a lock; the hot-path callback() reads the
    // volatile field without any synchronization or allocation.
    private static final XC_MethodHook[] EMPTY_CALLBACKS = new XC_MethodHook[0];
    private volatile XC_MethodHook[] callbacks = EMPTY_CALLBACKS;

    private final Member hookedMember;
    private final boolean isStatic;
    private volatile boolean firstHitLogged = false;
    private volatile boolean unexpectedOriginalThrowableLogged = false;

    // Set to the backup Method returned by lsplant::Hook. Invoking it runs the
    // original (unhooked) implementation.
    private volatile Method backup;

    public LspHooker(Member hookedMember, boolean isStatic) {
        this.hookedMember = hookedMember;
        this.isStatic = isStatic;
    }

    // Copy-on-write: creates a new array with the callback appended.
    // Only called during hook installation (cold path), never on dispatch.
    public synchronized void addCallback(XC_MethodHook cb) {
        XC_MethodHook[] old = callbacks;
        XC_MethodHook[] updated = Arrays.copyOf(old, old.length + 1);
        updated[old.length] = cb;
        callbacks = updated;
    }

    // Stops a callback from receiving dispatch. Note: this does NOT un-install
    // the underlying LSPlant hook (the shim does not expose lsplant::UnHook);
    // the backup trampoline stays in place and the method remains hooked, but
    // this callback no longer participates in before/after dispatch.
    // Copy-on-write: creates a new array without the removed callback.
    public synchronized void removeCallback(XC_MethodHook cb) {
        XC_MethodHook[] old = callbacks;
        int idx = -1;
        for (int i = 0; i < old.length; i++) {
            if (old[i] == cb) { idx = i; break; }
        }
        if (idx < 0) return;
        if (old.length == 1) {
            callbacks = EMPTY_CALLBACKS;
            return;
        }
        XC_MethodHook[] updated = new XC_MethodHook[old.length - 1];
        System.arraycopy(old, 0, updated, 0, idx);
        System.arraycopy(old, idx + 1, updated, idx, old.length - idx - 1);
        callbacks = updated;
    }

    public void setBackup(Method backup) {
        this.backup = backup;
        if (backup != null) backup.setAccessible(true);
    }

    public Member getHookedMember() {
        return hookedMember;
    }

    // Entry point invoked by LSPlant in place of the target method.
    public Object callback(Object[] args) throws Throwable {
        if (!firstHitLogged) {
            firstHitLogged = true;
            Log.w(TAG, "audit:LSPlant callback first hit target=" + hookedMember
                    + " argc=" + (args == null ? -1 : args.length));
        }

        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = hookedMember;

        Object receiver;
        Object[] callArgs;
        if (isStatic) {
            receiver = null;
            callArgs = args != null ? args : new Object[0];
        } else {
            receiver = (args != null && args.length > 0) ? args[0] : null;
            int n = (args != null && args.length > 0) ? args.length - 1 : 0;
            callArgs = new Object[n];
            if (n > 0) System.arraycopy(args, 1, callArgs, 0, n);
        }
        param.thisObject = receiver;
        param.args = callArgs;

        // Copy-on-write: volatile read is lock-free and allocation-free.
        XC_MethodHook[] snapshot = callbacks;

        int beforeIdx = 0;
        for (; beforeIdx < snapshot.length; beforeIdx++) {
            param.resetReturnEarly();
            try {
                snapshot[beforeIdx].callBefore(param);
            } catch (Throwable t) {
                Log.w(TAG, "before hook callback failed for " + hookedMember + ": " + t, t);
            }
            if (param.shouldReturnEarly()) {
                beforeIdx++;
                break;
            }
        }

        if (!param.shouldReturnEarly()) {
            try {
                Object result = invokeOriginal(receiver, callArgs);
                param.setResult(result);
                param.resetReturnEarly();
            } catch (Throwable t) {
                if (!isDeclaredThrowable(t) && !unexpectedOriginalThrowableLogged) {
                    unexpectedOriginalThrowableLogged = true;
                    Log.w(TAG, "original invocation threw undeclared exception for "
                            + hookedMember + ": " + t, t);
                }
                param.setThrowable(t);
                param.resetReturnEarly();
            }
        }

        for (int i = beforeIdx - 1; i >= 0; i--) {
            try {
                snapshot[i].callAfter(param);
            } catch (Throwable t) {
                Log.w(TAG, "after hook callback failed for " + hookedMember + ": " + t, t);
            }
        }

        return param.getResultOrThrowable();
    }

    private Object invokeOriginal(Object receiver, Object[] callArgs) throws Throwable {
        try {
            return invokeOriginalInternal(receiver, callArgs);
        } catch (OriginalMethodThrowable e) {
            throw e.throwable;
        }
    }

    private Object invokeOriginalInternal(Object receiver, Object[] callArgs) throws Throwable {
        if (backup == null) {
            throw new IllegalStateException(
                    "LspHooker: backup method not set for " + hookedMember);
        }
        int backupParamCount = backup.getParameterTypes().length;
        boolean backupStatic = Modifier.isStatic(backup.getModifiers());
        if (backupParamCount == callArgs.length) {
            try {
                return invokeBackup(receiver, callArgs);
            } catch (NullPointerException | IllegalArgumentException first) {
                if (receiver != null) {
                    Log.w(TAG, "backup direct receiver invoke failed for " + hookedMember
                            + "; retrying null receiver: " + first);
                    return invokeBackup(null, callArgs);
                }
                throw first;
            }
        }
        if (backupParamCount == callArgs.length + 1) {
            Object[] full = new Object[callArgs.length + 1];
            full[0] = receiver;
            System.arraycopy(callArgs, 0, full, 1, callArgs.length);
            Object preferredTarget = backupStatic ? null : receiver;
            Log.w(TAG, "backup arg count includes receiver for " + hookedMember
                    + " backupStatic=" + backupStatic
                    + " backupArgs=" + backupParamCount
                    + " callArgs=" + callArgs.length);
            try {
                return invokeBackup(preferredTarget, full);
            } catch (NullPointerException | IllegalArgumentException first) {
                Object fallbackTarget = preferredTarget == null ? receiver : null;
                if (fallbackTarget != preferredTarget) {
                    Log.w(TAG, "backup receiver-arg invoke failed for " + hookedMember
                            + "; retrying alternate target: " + first);
                    return invokeBackup(fallbackTarget, full);
                }
                throw first;
            }
        }
        throw new IllegalArgumentException("backup argument mismatch for " + hookedMember
                + " backup=" + backup
                + " backupStatic=" + backupStatic
                + " backupArgs=" + backupParamCount
                + " callArgs=" + callArgs.length
                + " receiver=" + receiver);
    }

    private Object invokeBackup(Object target, Object[] args) throws Throwable {
        try {
            return backup.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new OriginalMethodThrowable(cause != null ? cause : e);
        }
    }

    private boolean isDeclaredThrowable(Throwable throwable) {
        if (!(hookedMember instanceof Method) || throwable == null) {
            return false;
        }
        for (Class<?> declared : ((Method) hookedMember).getExceptionTypes()) {
            if (declared.isInstance(throwable)) {
                return true;
            }
        }
        return false;
    }

    private static final class OriginalMethodThrowable extends Exception {
        final Throwable throwable;

        OriginalMethodThrowable(Throwable throwable) {
            super(null, throwable, false, false);
            this.throwable = throwable;
        }
    }
}
