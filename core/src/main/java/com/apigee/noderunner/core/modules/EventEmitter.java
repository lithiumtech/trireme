package com.apigee.noderunner.core.modules;

import com.apigee.noderunner.core.NodeModule;
import com.apigee.noderunner.core.internal.ScriptRunner;
import com.apigee.noderunner.core.internal.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.FunctionObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSStaticFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.apigee.noderunner.core.internal.ArgUtils.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Implementation of EventEmitter from node0.8.15
 */
public class EventEmitter
    implements NodeModule
{
    public static final String CLASS_NAME = "EventEmitter";

    private static final Logger log = LoggerFactory.getLogger(EventEmitter.class);
    private static final int DEFAULT_LIST_LEN = 4;

    public static final String EVENT_NEW_LISTENER = "newListener";
    public static final int DEFAULT_MAX_LISTENERS = 10;

    @Override
    public String getModuleName() {
        return "events";
    }

    @Override
    public Object registerExports(Context cx, Scriptable scope, ScriptRunner runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, EventEmitterImpl.class);
        Scriptable exports = cx.newObject(scope);
        exports.put("EventEmitter", exports,
                    new FunctionObject("EventEmitter",
                                       Utils.findMethod(EventEmitter.class, "newEventEmitter"),
                                       exports));
        return exports;
    }

    public static Object newEventEmitter(Context ctx, Object[] args, Function caller, boolean inNew)
    {
        return ctx.newObject(caller, CLASS_NAME, args);
    }

    public static class EventEmitterImpl
        extends ScriptableObject
    {

        private final HashMap<String, List<Lsnr>> listeners = new HashMap<String, List<Lsnr>>();
        private int maxListeners = DEFAULT_MAX_LISTENERS;

        @Override
        public String getClassName() {
            return CLASS_NAME;
        }

        @JSFunction
        public void addListener(String event, Function listener)
        {
            on(event, listener);
        }

        @JSFunction
        public void on(String event, Function listener)
        {
            register(event, listener, false);
        }

        @JSFunction
        public void once(String event, Function listener)
        {
            register(event, listener, true);
        }

        public void register(String event, Function listener, boolean once)
        {
            fireEvent(EVENT_NEW_LISTENER, event, listener);

            List<Lsnr> ls = listeners.get(event);
            if (ls == null) {
                ls = new ArrayList<Lsnr>(DEFAULT_LIST_LEN);
                listeners.put(event, ls);
            }
            ls.add(new Lsnr(listener, once));

            if (log.isDebugEnabled()) {
                log.debug("Now {} listeners registered for {}", ls.size(), event);
            }
            if (ls.size() > maxListeners) {
                log.warn("{} listeners assigned for event type {}", ls.size(), event);
            }
        }

        @JSFunction
        public void removeListener(String event, Function listener)
        {
            List<Lsnr> ls = listeners.get(event);
            if (ls != null) {
                Iterator<Lsnr> i = ls.iterator();
                while (i.hasNext()) {
                    Lsnr l = i.next();
                    if (listener.equals(l.function)) {
                        i.remove();
                    }
                }
            }
        }

        @JSFunction
        public void removeAllListeners(String event)
        {
            listeners.remove(event);
        }

        @JSFunction
        public void setMaxListeners(int max)
        {
            this.maxListeners = max;
        }

        @JSFunction
        public static Scriptable listeners(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            String event = stringArg(args, 0);
            List<Lsnr> ls = ((EventEmitterImpl)thisObj).listeners.get(event);
            ArrayList<Function> ret = new ArrayList<Function>();
            if (ls == null) {
                return ctx.newArray(thisObj, 0);
            }

            Object[] funcs = new Object[ls.size()];
            int i = 0;
            for (Lsnr l : ls) {
                funcs[i++] = l.function;
            }
            return ctx.newArray(thisObj, funcs);
        }

        @JSFunction
        public static void emit(Context ctx, Scriptable thisObj, Object[] args, Function caller)
        {
            EventEmitterImpl emitter = (EventEmitterImpl)thisObj;
            String event = (String)Context.jsToJava(args[0], String.class);
            Object[] funcArgs = null;
            if (args.length > 1) {
                funcArgs = new Object[args.length - 1];
                System.arraycopy(args, 1, funcArgs, 0, args.length - 1);
            }
            emitter.fireEvent(event, funcArgs);
        }

        public boolean fireEvent(String event, Object... args)
        {
            boolean handled = false;
            List<Lsnr> ls = listeners.get(event);
            if (ls != null) {
                // Make a copy of the list because listeners may try to
                // modify the list while we're executing it.
                ArrayList<Lsnr> toFire = new ArrayList<Lsnr>(ls);
                Context c = Context.getCurrentContext();

                // Now clean up listeners that should only fire once.
                Iterator<Lsnr> i = ls.iterator();
                while (i.hasNext()) {
                    Lsnr l = i.next();
                    if (l.once) {
                        i.remove();
                    }
                }

                // Now actually fire. If we fail here we still have cleaned up.
                for (Lsnr l : toFire) {
                    if (log.isDebugEnabled()) {
                        log.debug("Sending {} to {}", event, l.function);
                    }
                    l.function.call(c, l.function, l.function, args);
                    handled = true;
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("Event {} fired. handled = {}", event, handled);
            }
            return handled;
        }

        private static final class Lsnr
        {
            final Function function;
            final boolean once;

            Lsnr(Function function, boolean once)
            {
                this.function = function;
                this.once = once;
            }
        }
    }
}
