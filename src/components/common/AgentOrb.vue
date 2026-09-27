<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAgent } from '@/stores/agent'
import { usePointer } from '@/composables/usePointer'
import { resolveLook } from '@/agentLook'

// Agent 的虚拟形象：流动的光斑 + 会眨眼、会看人的眼睛，表情跟随 Agent 状态
const props = defineProps({
  size: { type: Number, default: 32 },
  mood: String, // 不传则跟随 agent store：idle | thinking | listening | happy | offline
  look: Object, // 不传则跟随 agent store：{ preset, custom, glasses }
})

const agent = useAgent()
const mood = computed(() => props.mood || agent.mood)
const hasFace = computed(() => props.size >= 18)
const glasses = computed(() => (props.look || agent.look).glasses)
const colorVars = computed(() => {
  const p = props.look ? resolveLook(props.look) : agent.palette
  return {
    '--base': p.base,
    '--c1': p.colors[0],
    '--c2': p.colors[1],
    '--c3': p.colors[2],
    '--c4': p.colors[3],
  }
})

const el = ref(null)
const pointer = usePointer()
const look = ref({ x: 0, y: 0 })
const blinking = ref(false)
const reduced = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches

// 视线最大偏移
const reach = computed(() => props.size * 0.09)

function lookAtPointer() {
  if (mood.value !== 'idle' && mood.value !== 'happy') return
  if (!el.value || pointer.t === 0) return
  const r = el.value.getBoundingClientRect()
  const dx = pointer.x - (r.left + r.width / 2)
  const dy = pointer.y - (r.top + r.height / 2)
  const dist = Math.hypot(dx, dy) || 1
  const k = Math.min(1, dist / 240) * reach.value
  look.value = { x: (dx / dist) * k, y: (dy / dist) * k }
}
watch(() => pointer.t, lookAtPointer)

// 不同状态下的固定视线
watch(
  mood,
  (m) => {
    const r = reach.value
    if (m === 'offline') look.value = { x: 0, y: r * 0.5 }
    else if (m === 'thinking') look.value = { x: r * 0.7, y: -r * 0.9 }
    else if (m === 'listening') look.value = { x: 0, y: r }
    else lookAtPointer()
  },
  { immediate: true },
)

// 随机眨眼，偶尔连眨两下
let blinkTimer, wanderTimer
function blink() {
  blinking.value = true
  setTimeout(() => (blinking.value = false), 130)
}
function scheduleBlink() {
  blinkTimer = setTimeout(() => {
    if (mood.value !== 'happy') {
      blink()
      if (Math.random() < 0.25) setTimeout(blink, 260)
    }
    scheduleBlink()
  }, 2200 + Math.random() * 3800)
}
// 鼠标一段时间没动时，自己东张西望
function wander() {
  if (mood.value === 'idle' && Date.now() - pointer.t > 4000) {
    const r = reach.value * 0.8
    look.value = Math.random() < 0.35 ? { x: 0, y: 0 } : { x: (Math.random() * 2 - 1) * r, y: (Math.random() * 2 - 1) * r * 0.6 }
  }
}

onMounted(() => {
  if (reduced) return
  scheduleBlink()
  wanderTimer = setInterval(wander, 2600)
})
onBeforeUnmount(() => {
  clearTimeout(blinkTimer)
  clearInterval(wanderTimer)
})
</script>

<template>
  <span
    ref="el"
    class="orb"
    :class="[mood, { blinking, 'with-glasses': glasses && hasFace }]"
    :style="{ '--s': size + 'px', '--lx': look.x + 'px', '--ly': look.y + 'px', ...colorVars }"
    role="img"
    :aria-label="`Agent ${mood === 'thinking' ? '思考中' : ''}`"
  >
    <span class="halo" />
    <!-- body 负责呼吸/跳跃；ball 裁剪光斑；眼镜在 ball 之外，移动时不被边缘裁掉 -->
    <span class="body">
      <span class="ball">
        <i class="blob b1" />
        <i class="blob b2" />
        <i class="blob b3" />
        <i class="blob b4" />
        <span class="shine" />
        <span v-if="hasFace" class="face">
          <i class="eye" />
          <i class="eye" />
        </span>
      </span>
      <svg v-if="glasses && hasFace" class="glasses" viewBox="0 0 100 100" aria-hidden="true">
        <!-- 宽方形圆角大镜框，覆盖到脸部左右两侧，无镜腿 -->
        <rect class="lens" x="5" y="33" width="42.5" height="38" rx="13" />
        <rect class="lens" x="52.5" y="33" width="42.5" height="38" rx="13" />
        <path class="bridge" d="M47.5 44 Q50 40.5 52.5 44" />
        <!-- 镜片反光 -->
        <path class="glare" d="M9.5 53 L16.5 37.5" />
        <path class="glare" d="M11.5 61.5 L14.5 55" />
        <path class="glare" d="M57 53 L64 37.5" />
        <path class="glare" d="M59 61.5 L62 55" />
      </svg>
    </span>
  </span>
</template>

<style scoped>
.orb {
  --t: 1; /* 动画速度系数，思考时变快 */
  position: relative;
  flex: none;
  display: inline-block;
  width: var(--s);
  height: var(--s);
}
.body {
  position: absolute;
  inset: 0;
  animation: breathe 3.6s ease-in-out infinite;
}
.ball {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  overflow: hidden;
  background: var(--base);
  box-shadow: 0 2px 10px color-mix(in srgb, var(--c2) 35%, transparent), inset 0 0 0 1px rgba(255, 255, 255, 0.55);
  transition: background 0.4s;
}

/* 流动的光斑 */
.blob {
  position: absolute;
  width: 75%;
  height: 75%;
  border-radius: 50%;
  filter: blur(calc(var(--s) * 0.1));
}
.b1 {
  left: -15%;
  top: -15%;
  background: var(--c1);
  animation: drift1 calc(var(--t) * 7s) ease-in-out infinite alternate;
}
.b2 {
  right: -20%;
  top: -5%;
  background: var(--c2);
  animation: drift2 calc(var(--t) * 9s) ease-in-out infinite alternate;
}
.b3 {
  left: 5%;
  bottom: -25%;
  background: var(--c3);
  animation: drift3 calc(var(--t) * 11s) ease-in-out infinite alternate;
}
.b4 {
  left: 25%;
  top: 20%;
  width: 55%;
  height: 55%;
  background: var(--c4);
  animation: drift4 calc(var(--t) * 8s) ease-in-out infinite alternate;
}
.shine {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 24%, rgba(255, 255, 255, 0.85) 0 10%, rgba(255, 255, 255, 0.18) 30%, transparent 55%);
  pointer-events: none;
}

/* 眼睛 */
.face {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: calc(var(--s) * 0.15);
  padding-top: calc(var(--s) * 0.04);
  transform: translate(var(--lx), var(--ly));
  transition: transform 0.35s cubic-bezier(0.3, 1.4, 0.5, 1), gap 0.3s;
}
/* 戴眼镜时两眼分开，各自落在镜片中央 */
.with-glasses .face {
  gap: calc(var(--s) * 0.34);
}
.eye {
  width: calc(var(--s) * 0.1);
  height: calc(var(--s) * 0.2);
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 0 calc(var(--s) * 0.05) rgba(30, 50, 140, 0.35);
  transition: height 0.2s, transform 0.12s ease-out, width 0.2s, border-radius 0.2s;
}
.glasses {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
  pointer-events: none;
  /* 与眼睛同步移动 */
  transform: translate(var(--lx), var(--ly));
  transition: transform 0.35s cubic-bezier(0.3, 1.4, 0.5, 1);
  fill: none;
  stroke: #1f2440;
  stroke-linecap: round;
  stroke-linejoin: round;
  filter: drop-shadow(0 calc(var(--s) * 0.01) calc(var(--s) * 0.02) rgba(20, 24, 60, 0.25));
}
.glasses .lens {
  fill: rgba(255, 255, 255, 0.14);
  stroke-width: 4;
}
.glasses .bridge {
  stroke-width: 3.6;
}
.glasses .glare {
  stroke: rgba(255, 255, 255, 0.55);
  stroke-width: 2;
}
.blinking .eye {
  transform: scaleY(0.1);
}
.orb:hover .eye {
  height: calc(var(--s) * 0.23);
}

/* 思考：光斑加速、眯眼上看、外圈光环旋转 */
.thinking {
  --t: 0.28;
}
.thinking .eye {
  height: calc(var(--s) * 0.12);
}
.halo {
  position: absolute;
  inset: calc(var(--s) * -0.12);
  border-radius: 50%;
  background: conic-gradient(from 0deg, transparent 0 55%, var(--c1) 75%, var(--c2) 92%, transparent);
  -webkit-mask: radial-gradient(circle, transparent 62%, #000 64%);
  mask: radial-gradient(circle, transparent 62%, #000 64%);
  opacity: 0;
  transform: scale(0.85);
  transition: opacity 0.3s, transform 0.3s;
}
.thinking .halo {
  opacity: 1;
  transform: scale(1);
  animation: spin 1.1s linear infinite;
}
.thinking .body {
  animation: breathe 1.2s ease-in-out infinite;
}

/* 离线：AI 不可用，变灰、半闭眼、光斑停住 */
.offline .ball {
  filter: grayscale(0.85) brightness(0.95);
}
.offline .blob {
  animation-play-state: paused;
}
.offline .body {
  animation: none;
}
.offline .eye {
  height: calc(var(--s) * 0.06);
}

/* 开心：^ ^ 眯眼 + 跳一下 */
.happy .body {
  animation: hop 0.7s cubic-bezier(0.3, 1.6, 0.5, 1);
}
.happy .eye {
  width: calc(var(--s) * 0.15);
  height: calc(var(--s) * 0.08);
  background: transparent;
  border: calc(var(--s) * 0.035) solid #fff;
  border-bottom: 0;
  border-radius: 999px 999px 0 0;
  box-shadow: none;
  filter: drop-shadow(0 0 calc(var(--s) * 0.04) rgba(30, 50, 140, 0.4));
}

@keyframes drift1 {
  to {
    transform: translate(35%, 25%) scale(1.15);
  }
}
@keyframes drift2 {
  to {
    transform: translate(-30%, 35%) scale(0.9);
  }
}
@keyframes drift3 {
  to {
    transform: translate(30%, -30%) scale(1.2);
  }
}
@keyframes drift4 {
  to {
    transform: translate(-25%, -20%) scale(0.8);
  }
}
@keyframes breathe {
  50% {
    transform: scale(1.04);
  }
}
@keyframes hop {
  40% {
    transform: translateY(-18%) scale(1.06);
  }
  70% {
    transform: translateY(2%) scale(0.98, 1.02);
  }
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .body,
  .blob,
  .halo,
  .thinking .halo {
    animation: none !important;
  }
  .face,
  .glasses {
    transition: none;
  }
}
</style>
