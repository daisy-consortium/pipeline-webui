if (!Date.now) {
	Date.now = function now() {
		return +(new Date);
	};
}

Notifications = {
	lastWebSocketHeartbeat: Date.now(),
	lastXHRHeartbeat: Date.now(),
	WebSocket: typeof MozWebSocket !== "undefined" ? MozWebSocket : typeof WebSocket !== "undefined" ? WebSocket : null,
	websocket: null,
	websocketURL: "",
	xhrURL: "",
	handlers: {},
	
	init: function(websocketURL, xhrURL) {
		Notifications.websocketURL = websocketURL;
		Notifications.xhrURL = xhrURL;
    	Notifications.start();
	},

    start: function() {
        // Watchdog timer (switches to XHR if WebSockets are unavailable or disconnected)
        window.clearInterval(Notifications.stopWatchdog);
        Notifications.watchdog = window.setInterval(function(){
            if (Date.now() - Notifications.lastWebSocketHeartbeat > 5000) {
                // More than 5 seconds since last heartbeat; switch to XHR
                if (Notifications.websocket !== null) {
                    Notifications.websocket.close();
                    Notifications.websocket = null;
                }
                $.ajax({
                    url: Notifications.xhrURL,
                    dataType: 'json',
                    cache: false,
                    data: Date.now()+"",
                    success: function(data, textStatus, jqXHR){
                        for (var i = 0; i < data.length; i++) {
                            Notifications.handleNotifications(data[i]);
                        }
                    }
                });
            }
            if (Notifications.WebSocket && Date.now() - Notifications.lastWebSocketHeartbeat > 30000) {
                // retry websockets
                Notifications.lastWebSocketHeartbeat = Date.now() - 5000;
                Notifications.openWebSocket();
            }
        }, 1000);

        // Try WebSockets first
        $(Notifications.openWebSocket);
    },

    stop: function() {
        window.clearInterval(Notifications.watchdog);
        Notifications.watchdog = null;
        if (Notifications.websocket !== null)
            Notifications.websocket.close();
    },
	
	handleNotifications: function(notification) {
		if (notification.error) {
			if (Notifications.websocket !== null) {
            	Notifications.websocket.close();
            	Notifications.websocket = null;
			}
            return;
            
        } else {
        	if (Notifications.websocket !== null) {
        		Notifications.lastWebSocketHeartbeat = Date.now();
        	} else {
        		Notifications.lastXHRHeartbeat = Date.now();
        	}
        	
        	if (Notifications.handlers[notification.kind]) {
        		for (var i = 0; i < Notifications.handlers[notification.kind].length; i++) {
                    var handler = Notifications.handlers[notification.kind][i];
                    handler.fn(notification.data, handler.data);
        		}
        	}
        }
	},
	
	openWebSocket: function() {
        if (!Notifications.WebSocket) return;
        Notifications.websocket = new Notifications.WebSocket(Notifications.websocketURL);
        Notifications.websocket.onmessage = function(event) {Notifications.handleNotifications(JSON.parse(event.data));};
        Notifications.websocket.onerror = function() {}
        Notifications.websocket.onclose = function() {Notifications.websocket = null;}
	},
	
	listen: function(kind, fn, data) {
        if (!$.isArray(Notifications.handlers[kind]))
            Notifications.handlers[kind] = new Array();
		Notifications.handlers[kind].push({ fn:fn, data:data });
	}
};