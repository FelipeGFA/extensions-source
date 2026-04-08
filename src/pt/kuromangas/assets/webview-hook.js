(function () {
  if (window.__kuroHooked) return;
  window.__kuroHooked = true;

  var I = window.__IFACE_PLACEHOLDER__;
  if (!I) return;

  var expectPages = window.__kuroExpectPages === true;
  var lastPayload = "";

  function isUsefulPayload(data) {
    if (!data || typeof data !== "object") return false;
    if (data._v_secure) return false;

    if (expectPages) {
      return Array.isArray(data.pages) && data.pages.length > 0;
    }

    return (
      (Array.isArray(data.data) && data.pagination) ||
      !!(data.manga && data.manga.id) ||
      Array.isArray(data.chapters) ||
      Array.isArray(data.pages) ||
      (typeof data.token === "string" && data.token.length > 20) ||
      !!(data.user && data.user.id)
    );
  }

  function findPayload(data) {
    if (isUsefulPayload(data)) return data;

    if (!data || typeof data !== "object" || Array.isArray(data)) return null;

    var keys = Object.keys(data);
    for (var i = 0; i < keys.length; i++) {
      var child = data[keys[i]];
      if (isUsefulPayload(child)) return child;
    }

    return null;
  }

  function sendPayload(data, source) {
    try {
      var payload = findPayload(data);
      if (!payload) return false;

      var json = JSON.stringify(payload);
      if (!json || json.length < 50) return false;
      if (json === lastPayload) return false;

      lastPayload = json;
      I.onDataParsed(json);
      return true;
    } catch (e) {
      return false;
    }
  }

  try {
    var originalJSONParse = JSON.parse;
    JSON.parse = function () {
      var parsed = originalJSONParse.apply(this, arguments);
      sendPayload(parsed, "JSON.parse");
      return parsed;
    };
  } catch (e) { }
})();
