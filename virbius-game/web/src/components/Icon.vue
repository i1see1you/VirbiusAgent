<script setup>
defineProps({
  name: { type: String, required: true },
});
</script>

<template>
  <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <g v-if="name === 'target'">
      <circle cx="12" cy="12" r="8" />
      <circle cx="12" cy="12" r="3.2" />
      <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
    </g>
    <g v-else-if="name === 'inject'">
      <path d="M14.5 4.5l5 5" />
      <path d="M16.5 6.5l-8.2 8.2c-.8.8-2.6.5-3.7-.6s-1.4-2.9-.6-3.7L12.2 2.2" />
      <path d="M8 16l-5 5" />
      <path d="M11 9l4 4" />
    </g>
    <g v-else-if="name === 'globe'">
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18" />
      <path d="M12 3a14 14 0 0 1 0 18" />
      <path d="M12 3a14 14 0 0 0 0 18" />
    </g>
    <g v-else-if="name === 'flag'">
      <path d="M5 21V5" />
      <path d="M5 5h10l-2 3.5L15 12H5" />
    </g>
    <g v-else-if="name === 'trophy'">
      <path d="M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0V4z" />
      <path d="M7 6H5a3 3 0 0 0 3 5M17 6h2a3 3 0 0 1-3 5" />
    </g>
    <g v-else-if="name === 'bike'">
      <circle cx="6.5" cy="16.5" r="3" />
      <circle cx="17.5" cy="16.5" r="3" />
      <path d="M6.5 16.5L10 8h4l3.5 8.5M10 8l2 8.5M12 8V6" />
    </g>
    <g v-else-if="name === 'plane'">
      <path d="M10 17l-3 3v-4l8-5 4-7-7 4-5 8H3z" />
    </g>
    <g v-else-if="name === 'chat'">
      <path d="M5 6h10a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3H11l-4 3v-3H5a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z" />
      <path d="M8 10h6M8 13h4" />
    </g>
    <g v-else-if="name === 'smile'">
      <circle cx="12" cy="12" r="9" />
      <path d="M8 10h.01M16 10h.01M8.5 15c.8 1.2 2 1.8 3.5 1.8s2.7-.6 3.5-1.8" />
    </g>
    <g v-else-if="name === 'brain'">
      <path d="M9 4a3 3 0 0 0-3 3v1a3 3 0 0 0 0 6v1a3 3 0 0 0 3 3" />
      <path d="M15 4a3 3 0 0 1 3 3v1a3 3 0 0 1 0 6v1a3 3 0 0 1-3 3" />
      <path d="M9 4h6M9 20h6M12 4v16" />
    </g>
    <g v-else-if="name === 'chart'">
      <path d="M4 19h16" />
      <path d="M7 16V10M12 16V6M17 16v-7" />
    </g>
    <g v-else-if="name === 'code'">
      <path d="M8 8L4 12l4 4M16 8l4 4-4 4M13 6l-2 12" />
    </g>
    <g v-else-if="name === 'bag'">
      <path d="M6 8h12l-1 12H7L6 8z" />
      <path d="M9 8V6a3 3 0 0 1 6 0v2" />
    </g>
    <g v-else-if="name === 'mail'">
      <rect x="3" y="6" width="18" height="13" rx="2" />
      <path d="M3 8l9 6 9-6" />
    </g>
    <g v-else-if="name === 'scales'">
      <path d="M12 3v18M5 21h14" />
      <path d="M12 7h7l-2.2 5.5a2.4 2.4 0 1 1-4.6 0L12 7zM12 7H5l2.2 5.5a2.4 2.4 0 1 0 4.6 0L12 7z" />
    </g>
    <g v-else-if="name === 'chevron-left'">
      <path d="M15 5l-7 7 7 7" />
    </g>
    <g v-else-if="name === 'clock'">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3.2 1.8" />
    </g>
    <g v-else-if="name === 'gear'">
      <circle cx="12" cy="12" r="3" />
      <path d="M12 3v2M12 19v2M4.9 4.9l1.5 1.5M17.6 17.6l1.5 1.5M3 12h2M19 12h2M4.9 19.1l1.5-1.5M17.6 6.4l1.5-1.5" />
    </g>
    <g v-else-if="name === 'grid'">
      <rect x="4" y="4" width="6.5" height="6.5" rx="1.2" />
      <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.2" />
      <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.2" />
      <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.2" />
    </g>
  </svg>
</template>
