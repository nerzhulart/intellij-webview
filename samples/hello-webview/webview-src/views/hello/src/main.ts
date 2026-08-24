import { apiId, type WebViewCallable, webView } from "@nerzhulart/intellij-webview-sdk"

interface HelloHostApi extends WebViewCallable {
  buttonClicked(): Promise<void>
}

const hostApi = webView.callable(apiId<HelloHostApi>()("hello.host"))
const button = document.querySelector<HTMLButtonElement>("#hello-button")

if (!button) {
  throw new Error("#hello-button is missing")
}

button.addEventListener("click", () => {
  void hostApi.buttonClicked()
})