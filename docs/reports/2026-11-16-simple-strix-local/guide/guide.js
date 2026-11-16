"use strict";

const steps = [...document.querySelectorAll("input[data-step]")];
const progress = document.querySelector("progress");
const progressText = document.querySelector("#progress-text");
function updateProgress() {
  const count = steps.filter(input => input.checked).length;
  progress.value = count;
  progressText.textContent = `${count} / ${steps.length}`;
}
steps.forEach(input => input.addEventListener("change", updateProgress));
updateProgress();

const dialog = document.querySelector("#image-dialog");
const zoom = document.querySelector("#zoom-image");
document.querySelectorAll(".image-button").forEach(button => {
  button.addEventListener("click", () => {
    const source = button.querySelector("img");
    if (typeof dialog.showModal !== "function") {
      window.open(source.src, "_blank", "noopener");
      return;
    }
    zoom.src = source.src;
    zoom.alt = source.alt;
    document.querySelector("#image-title").textContent = source.alt;
    dialog.showModal();
    document.querySelector("#close-dialog").focus();
  });
});
document.querySelector("#close-dialog").addEventListener("click", () => dialog.close());
dialog.addEventListener("click", event => { if (event.target === dialog) dialog.close(); });
document.querySelector("#print").addEventListener("click", () => window.print());

let toastTimer;
function toast(message) {
  const target = document.querySelector("#toast");
  target.textContent = message;
  target.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => target.classList.remove("show"), 2500);
}
document.querySelectorAll(".copy").forEach(button => {
  button.addEventListener("click", async () => {
    const value = button.parentElement.querySelector("code").textContent;
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(value);
      else {
        const input = document.createElement("textarea");
        input.value = value;
        input.style.position = "fixed";
        input.style.opacity = "0";
        document.body.append(input);
        input.select();
        const copied = document.execCommand("copy");
        input.remove();
        if (!copied) throw new Error("copy unavailable");
      }
      toast("명령을 복사했습니다. 터미널에서 실행하세요.");
    } catch {
      toast("명령을 선택한 뒤 직접 복사하세요.");
    }
  });
});

const links = [...document.querySelectorAll("nav a")];
if ("IntersectionObserver" in window) {
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) if (entry.isIntersecting) {
      links.forEach(link => {
        const selected = link.hash === `#${entry.target.id}`;
        link.classList.toggle("active", selected);
        if (selected) link.setAttribute("aria-current", "location");
        else link.removeAttribute("aria-current");
      });
    }
  }, {rootMargin: "-5% 0px -65% 0px", threshold: 0});
  document.querySelectorAll("main > [id]").forEach(section => observer.observe(section));
}
