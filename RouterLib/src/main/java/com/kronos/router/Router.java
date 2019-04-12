package com.kronos.router;


import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.text.TextUtils;

import com.kronos.router.exception.ContextNotProvided;
import com.kronos.router.exception.NotInitException;
import com.kronos.router.exception.RouteNotFoundException;
import com.kronos.router.model.HostParams;
import com.kronos.router.model.RouterOptions;
import com.kronos.router.model.RouterParams;
import com.kronos.router.utils.RouterUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Router {

    private static Router _router;

    public static Router sharedRouter() {  // 第一次检查
        if (_router == null) {
            synchronized (Router.class) {
                // 第二次检查
                if (_router == null) {
                    _router = new Router();
                }
            }
        }
        return _router;
    }


    private final Map<String, RouterParams> _cachedRoutes = new HashMap<>();
    private Application _context;
    private final Map<String, HostParams> hosts = new HashMap<>();
    private RouterLoader loader;

    private Router() {
        loader = new RouterLoader();
    }

    public void attachApplication(Application context) {
        this._context = context;
        loader.attach(context);
    }

    private Application getContext() {
        return this._context;
    }

    public static void map(String url, RouterCallback callback) {
        RouterOptions options = new RouterOptions();
        options.setCallback(callback);
        map(url, null, options);
    }

    public static void map(String url, Class<? extends Activity> mClass) {
        map(url, mClass, new RouterOptions());
    }

    public static void map(String url, Class<? extends Activity> mClass, @Nullable Class<? extends Fragment> targetFragment) {
        map(url, mClass, targetFragment, null);
    }

    public static void map(String url, Class<? extends Activity> mClass, @Nullable Class<? extends Fragment> targetFragment,
                           Bundle bundle) {
        RouterOptions options = new RouterOptions(bundle);
        assert targetFragment != null;
        options.putParams("target", targetFragment.getName());
        map(url, mClass, options);
    }


    public static void map(String url, Class<? extends Activity> mClass, RouterOptions options) {
        if (options == null) {
            options = new RouterOptions();
        }
        Uri uri = Uri.parse(url);
        options.setOpenClass(mClass);
        HostParams hostParams;
        if (sharedRouter().hosts.containsKey(uri.getHost())) {
            hostParams = sharedRouter().hosts.get(uri.getHost());
        } else {
            hostParams = new HostParams(uri.getHost());
            sharedRouter().hosts.put(hostParams.getHost(), hostParams);
        }
        hostParams.setRoute(uri.getPath(), options);
    }


    public void openExternal(String url) {
        this.openExternal(url, this._context);
    }


    public void openExternal(String url, Context context) {
        this.openExternal(url, null, context);
    }

    public void openExternal(String url, Bundle extras) {
        this.openExternal(url, extras, this._context);
    }


    public void openExternal(String url, Bundle extras, Context context) {
        if (context == null) {
            throw new ContextNotProvided("You need to supply a context for Router "
                    + this.toString());
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        this.addFlagsToIntent(intent, context);
        if (extras != null) {
            intent.putExtras(extras);
        }
        context.startActivity(intent);
    }

    public void open(String url) {
        this.open(url, this._context);
    }

    public void open(String url, Bundle extras) {
        this.open(url, extras, this._context);
    }

    public void open(String url, Context context) {
        this.open(url, null, context);
    }

    public void open(String url, Bundle extras, Context context) {
        if (context == null) {
            throw new ContextNotProvided("You need to supply a context for Router " + this.toString());
        }
        RouterParams params = this.paramsForUrl(url);
        RouterOptions options = params.getRouterOptions();
        if (options.getCallback() != null) {
            RouterContext routeContext = new RouterContext(params.getOpenParams(), extras, context);
            options.getCallback().run(routeContext);
            return;
        }


        Intent intent = this.intentFor(context, params);
        if (intent == null) {
            // Means the options weren't opening a new activity
            return;
        }
        if (extras != null) {
            intent.putExtras(extras);
        } else {
            Bundle bundle = new Bundle();
            intent.putExtras(bundle);
        }
        if (!(context instanceof Activity)) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private void addFlagsToIntent(Intent intent, Context context) {
        if (context == this._context) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    private Intent intentFor(RouterParams params) {
        RouterOptions options = params.getRouterOptions();
        Intent intent = new Intent();
        if (options.getDefaultParams() != null) {
            intent.putExtras(options.getDefaultParams());
        }
        for (Entry<String, String> entry : params.getOpenParams().entrySet()) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        return intent;
    }


    public boolean isCallbackUrl(String url) {
        RouterParams params = this.paramsForUrl(url);
        RouterOptions options = params.getRouterOptions();
        return options.getCallback() != null;
    }


    public Intent intentFor(Context context, String url) {
        RouterParams params = this.paramsForUrl(url);
        return intentFor(context, params);
    }

    private Intent intentFor(Context context, RouterParams params) {
        RouterOptions options = params.getRouterOptions();
        if (options.getCallback() != null) {
            return null;
        }

        Intent intent = intentFor(params);
        intent.setClass(context, options.getOpenClass());
        this.addFlagsToIntent(intent, context);
        return intent;
    }


    private RouterParams paramsForUrl(String url) {
        if (!loader.isLoadingFinish()) {
            throw new NotInitException("You need to wait init finish " + this.toString());
        }
        Uri parsedUri = Uri.parse(url);

        String urlPath = TextUtils.isEmpty(parsedUri.getPath()) ? "" : parsedUri.
                getPath().substring(1);
        if (this._cachedRoutes.get(url) != null) {
            return this._cachedRoutes.get(url);
        }

        String[] givenParts = urlPath.split("/");
        List<RouterParams> params = new ArrayList<>();
        HostParams hostParams = hosts.get(parsedUri.getHost());
        if (hostParams == null) {
            throw new RouteNotFoundException("No route found for url " + url);
        }
        for (Entry<String, RouterOptions> entry : hostParams.getRoutes().entrySet()) {
            RouterParams routerParams = getRouterParams(entry, givenParts);
            if (routerParams != null) {
                params.add(routerParams);
            }
        }

        RouterParams routerParams = params.size() == 1 ? params.get(0) : null;
        if (params.size() > 1) {
            for (RouterParams param : params) {
                if (TextUtils.equals(param.getRealPath(), urlPath)) {
                    routerParams = param;
                    break;
                }
            }
            if (routerParams == null) {
                Collections.sort(params, new Comparator<RouterParams>() {
                    @Override
                    public int compare(RouterParams o1, RouterParams o2) {
                        return o1.getWeight().compareTo(o2.getWeight());
                    }
                });
                routerParams = params.get(0);
            }
        }
        if (routerParams == null) {
            throw new RouteNotFoundException("No route found for url " + url);
        }
        for (String key : parsedUri.getQueryParameterNames()) {
            routerParams.getOpenParams().put(key, parsedUri.getQueryParameter(key));
        }
        routerParams.getOpenParams().put("targetUrl", url);
        this._cachedRoutes.put(url, routerParams);
        return routerParams;
    }

    private RouterParams getRouterParams(Entry<String, RouterOptions> entry, String[] givenParts) {
        RouterParams routerParams;
        String routerUrl = cleanUrl(entry.getKey());
        RouterOptions routerOptions = entry.getValue();
        String[] routerParts = routerUrl.split("/");
        if (routerParts.length != givenParts.length) {
            return null;
        }
        Map<String, String> givenParams = null;
        try {
            givenParams = RouterUtils.urlToParamsMap(givenParts, routerParts);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (givenParams == null) {
            return null;
        }
        routerParams = new RouterParams();
        routerParams.setUrl(entry.getKey());
        routerParams.setWeight(entry.getValue().getWeight());
        routerParams.setOpenParams(givenParams);
        routerParams.setRouterOptions(routerOptions);
        return routerParams;
    }


    private String cleanUrl(String url) {
        if (url.startsWith("/")) {
            return url.substring(1, url.length());
        }
        return url;
    }

    public boolean isLoadingFinish() {
        return loader.isLoadingFinish();
    }

}
