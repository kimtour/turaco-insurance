const slides = [...document.querySelectorAll('.slide')];
let index = Math.max(0, Math.min(slides.length - 1, Number(location.hash.replace('#slide-', '')) - 1 || 0));
function show(next) {
  index = Math.max(0, Math.min(slides.length - 1, next));
  slides.forEach((slide, i) => { slide.hidden = i !== index; });
  document.querySelector('#position').textContent = `${index + 1} / ${slides.length}`;
  document.querySelector('#previous').disabled = index === 0;
  document.querySelector('#next').disabled = index === slides.length - 1;
  history.replaceState(null, '', `#slide-${index + 1}`);
}
document.querySelector('#previous').addEventListener('click', () => show(index - 1));
document.querySelector('#next').addEventListener('click', () => show(index + 1));
document.addEventListener('keydown', event => {
  if (['ArrowRight', 'PageDown'].includes(event.key)) { event.preventDefault(); show(index + 1); }
  if (['ArrowLeft', 'PageUp'].includes(event.key)) { event.preventDefault(); show(index - 1); }
});
document.querySelector('#fullscreen').addEventListener('click', async event => {
  try { await document.documentElement.requestFullscreen(); }
  catch { event.target.textContent = 'Use browser full screen'; }
});
show(index);
