package com.github.lzyzsd.jsbridge;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.edaixi.platform.widget.webview.PageTitleEvent;
import com.edaixi.platform.widget.webview.TelEvent;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.greenrobot.event.EventBus;

@SuppressLint("SetJavaScriptEnabled")
public class BridgeWebView extends WebView implements WebViewJavascriptBridge {

	private final String TAG = "BridgeWebView";
	Context context;

	public static final String toLoadJs = "WebViewJavascriptBridge.js";
	Map<String, CallBackFunction> responseCallbacks = new HashMap<String, CallBackFunction>();
	Map<String, BridgeHandler> messageHandlers = new HashMap<String, BridgeHandler>();
	BridgeHandler defaultHandler = new DefaultHandler();

	private List<Message> startupMessage = new ArrayList<Message>();

	public List<Message> getStartupMessage() {
		return startupMessage;
	}

	public void setStartupMessage(List<Message> startupMessage) {
		this.startupMessage = startupMessage;
	}

	private long uniqueId = 0;

	public BridgeWebView(Context context, AttributeSet attrs) {
		super(context, attrs);
		this.context = context;
		init();
	}

	public BridgeWebView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		this.context = context;
		init();
	}

	public BridgeWebView(Context context) {
		super(context);
		this.context = context;
		init();
	}

	/**
	 * 
	 * @param handler
	 *            default handler,handle messages send by js without assigned handler name,
     *            if js message has handler name, it will be handled by named handlers registered by native
	 */
	public void setDefaultHandler(BridgeHandler handler) {
       this.defaultHandler = handler;
	}

    private void init() {
		this.setVerticalScrollBarEnabled(false);
		this.setHorizontalScrollBarEnabled(false);
		this.getSettings().setJavaScriptEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

		fixWebView();

		this.setWebViewClient(generateBridgeWebViewClient());
	}

	/**
	 * 安全问题
	 */
	@TargetApi(11)
	private void fixWebView() {
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1){
			removeJavascriptInterface("searchBoxJavaBridge_");
		}

	}

    protected BridgeWebViewClient generateBridgeWebViewClient() {
        return new BridgeWebViewClient(this);
    }

	void handlerReturnData(String url) {
		String functionName = BridgeUtil.getFunctionFromReturnUrl(url);
		CallBackFunction f = responseCallbacks.get(functionName);
		String data = BridgeUtil.getDataFromReturnUrl(url);
		if (f != null) {
			f.onCallBack(data);
			responseCallbacks.remove(functionName);
			return;
		}
	}

	@Override
	public void send(String data) {
		send(data, null);
	}

	@Override
	public void send(String data, CallBackFunction responseCallback) {
		doSend(null, data, responseCallback);
	}

	private void doSend(String handlerName, String data, CallBackFunction responseCallback) {
		Message m = new Message();
		if (!TextUtils.isEmpty(data)) {
			m.setData(data);
		}
		if (responseCallback != null) {
			String callbackStr = String.format(BridgeUtil.CALLBACK_ID_FORMAT, ++uniqueId + (BridgeUtil.UNDERLINE_STR + SystemClock.currentThreadTimeMillis()));
			responseCallbacks.put(callbackStr, responseCallback);
			m.setCallbackId(callbackStr);
		}
		if (!TextUtils.isEmpty(handlerName)) {
			m.setHandlerName(handlerName);
		}
		queueMessage(m);
	}

	private void queueMessage(Message m) {
		if (startupMessage != null) {
			startupMessage.add(m);
		} else {
			dispatchMessage(m);
		}
	}

	void dispatchMessage(Message m) {
        String messageJson = m.toJson();
        //escape special characters for json string
        messageJson = messageJson.replaceAll("(\\\\)([^utrn])", "\\\\\\\\$1$2");
        messageJson = messageJson.replaceAll("(?<=[^\\\\])(\")", "\\\\\"");
        String javascriptCommand = String.format(BridgeUtil.JS_HANDLE_MESSAGE_FROM_JAVA, messageJson);
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            this.loadUrl(javascriptCommand);
        }
    }

	void flushMessageQueue() {
		if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
			loadUrl(BridgeUtil.JS_FETCH_QUEUE_FROM_JAVA, new CallBackFunction() {

				@Override
				public void onCallBack(String data) {
					// deserializeMessage
					List<Message> list = null;
					try {
						list = Message.toArrayList(data);
					} catch (Exception e) {
                        e.printStackTrace();
						return;
					}
					if (list == null || list.size() == 0) {
						return;
					}
					for (int i = 0; i < list.size(); i++) {
						Message m = list.get(i);
						String responseId = m.getResponseId();
						// 是否是response
						if (!TextUtils.isEmpty(responseId)) {
							CallBackFunction function = responseCallbacks.get(responseId);
							String responseData = m.getResponseData();
							function.onCallBack(responseData);
							responseCallbacks.remove(responseId);
						} else {
							CallBackFunction responseFunction = null;
							// if had callbackId
							final String callbackId = m.getCallbackId();
							if (!TextUtils.isEmpty(callbackId)) {
								responseFunction = new CallBackFunction() {
									@Override
									public void onCallBack(String data) {
										Message responseMsg = new Message();
										responseMsg.setResponseId(callbackId);
										responseMsg.setResponseData(data);
										queueMessage(responseMsg);
									}
								};
							} else {
								responseFunction = new CallBackFunction() {
									@Override
									public void onCallBack(String data) {
										// do nothing
									}
								};
							}
							BridgeHandler handler;
							if (!TextUtils.isEmpty(m.getHandlerName())) {
								handler = messageHandlers.get(m.getHandlerName());
							} else {
								handler = defaultHandler;
							}
							if (handler != null){
								handler.handler(m.getData(), responseFunction);
							}
						}
					}
				}
			});
		}
	}

	public void loadUrl(String jsUrl, CallBackFunction returnCallback) {
		this.loadUrl(jsUrl);
		responseCallbacks.put(BridgeUtil.parseFunctionName(jsUrl), returnCallback);
	}

	/**
	 * register handler,so that javascript can call it
	 * 
	 * @param handlerName
	 * @param handler
	 */
	public void registerHandler(String handlerName, BridgeHandler handler) {
		if (handler != null) {
			messageHandlers.put(handlerName, handler);
		}
	}

	/**
	 * call javascript registered handler
	 *
     * @param handlerName
	 * @param data
	 * @param callBack
	 */
	public void callHandler(String handlerName, String data, CallBackFunction callBack) {
        doSend(handlerName, data, callBack);
	}



	public class BridgeWebViewClient extends WebViewClient {
		private BridgeWebView webView;

		public BridgeWebViewClient(BridgeWebView webView) {
			this.webView = webView;
		}

		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			try {
				url = URLDecoder.decode(url, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}

			if (url.startsWith(BridgeUtil.YY_RETURN_DATA)) { // 如果是返回数据
				webView.handlerReturnData(url);
				return true;
			} else if (url.startsWith(BridgeUtil.YY_OVERRIDE_SCHEMA)) { //
				webView.flushMessageQueue();
				return true;
			}else  if(url.startsWith("tel:")){
				String tel=url.substring("tel:".length());

				EventBus.getDefault().post(new TelEvent(tel));
				return true;
			}

			else {
				return super.shouldOverrideUrlLoading(view, url);
			}
		}

		@Override
		public void onPageStarted(WebView view, String url, Bitmap favicon) {
			super.onPageStarted(view, url, favicon);
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			super.onPageFinished(view, url);

			if (BridgeWebView.toLoadJs != null) {
				BridgeUtil.webViewLoadLocalJs(view, BridgeWebView.toLoadJs);
			}

			//
			if (webView.getStartupMessage() != null) {
				for (Message m : webView.getStartupMessage()) {
					webView.dispatchMessage(m);
				}
				webView.setStartupMessage(null);
			}

			//设置网页title
//			Toast.makeText(context, view.getTitle(), Toast.LENGTH_SHORT).show();
			PageTitleEvent pageEvent = new PageTitleEvent();
			pageEvent.setTitle(view.getTitle());
			EventBus.getDefault().post(pageEvent);
		}

		@Override
		public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
			super.onReceivedError(view, errorCode, description, failingUrl);
		}
	}
}
