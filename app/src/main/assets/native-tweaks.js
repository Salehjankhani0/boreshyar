/* تنظیمات «حس اپ بومی» — قبل از بارگذاری صفحه در WebView تزریق می‌شود.
 * برای اینکه متنی در سایت قابل انتخاب/کپی بماند (مثلاً کد پیگیری، شماره کارت)
 * به همان المان class="allow-select" یا data-selectable بدهید. */
(function () {
  try {
    if (window.__gcTweaks) return;
    window.__gcTweaks = 1;

    var EDITABLE = 'input,textarea,select,[contenteditable="true"],[contenteditable=""],.allow-select,[data-selectable]';

    var css =
      'html,body{-webkit-tap-highlight-color:transparent;overscroll-behavior:none;touch-action:manipulation;}' +
      '*{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;}' +
      EDITABLE.split(',').map(function (s) { return s + ',' + s + ' *'; }).join(',') +
      '{-webkit-user-select:text;user-select:text;}' +
      'img,a,svg{-webkit-user-drag:none;user-drag:none;}' +
      ':focus{outline:none;}' +
      'input,textarea{caret-color:auto;}';

    var st = document.createElement('style');
    st.setAttribute('data-gc-native', '1');
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);

    function editable(t) {
      return !!(t && t.closest && t.closest(EDITABLE));
    }

    // منوی لانگ‌پرس مرورگر / انتخاب متن / کشیدن عکس و لینک
    document.addEventListener('contextmenu', function (e) { if (!editable(e.target)) e.preventDefault(); }, true);
    document.addEventListener('selectstart', function (e) { if (!editable(e.target)) e.preventDefault(); }, true);
    document.addEventListener('dragstart', function (e) { e.preventDefault(); }, true);

    // جلوگیری از زوم pinch (دابل‌تپ‌زوم با touch-action:manipulation خاموش شده)
    document.addEventListener('gesturestart', function (e) { e.preventDefault(); }, true);

    // viewport: بدون زوم
    function fixViewport() {
      var m = document.querySelector('meta[name=viewport]');
      var v = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover';
      if (!m) { m = document.createElement('meta'); m.name = 'viewport'; (document.head || document.documentElement).appendChild(m); }
      if (m.content !== v) m.content = v;
    }
    fixViewport();
    document.addEventListener('DOMContentLoaded', fixViewport);
  } catch (e) { }
})();
