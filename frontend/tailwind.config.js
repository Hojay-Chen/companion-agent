module.exports = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        cocoa: {
          950: '#14100e',
          900: '#1a1512',
          850: '#201a16',
          800: '#272019',
          700: '#332a21',
          600: '#473a2d',
          500: '#6b5844',
          400: '#9a8168',
          300: '#c3ac90',
          200: '#dfd0ba',
          100: '#efe5d6',
          50: '#f9f4ec',
        },
        ember: {
          DEFAULT: '#d97757',
          soft: '#e8b467',
          deep: '#b45a3f',
          pale: '#f4d7c4',
        },
        rosewood: {
          DEFAULT: '#a85d6f',
          soft: '#c98a97',
        },
      },
      fontFamily: {
        sans: ['Inter', 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', '-apple-system', 'sans-serif'],
        editorial: ['"Iowan Old Style"', '"Palatino Linotype"', '"Noto Serif SC"', '"Songti SC"', 'Georgia', 'serif'],
      },
      boxShadow: {
        glow: '0 0 0 1px rgba(233,180,103,0.18), 0 8px 40px -12px rgba(0,0,0,0.55)',
        panel: '0 1px 0 rgba(255,255,255,0.03) inset, 0 12px 40px -16px rgba(0,0,0,0.6)',
      },
      keyframes: {
        fadeUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        pulseSoft: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.45' },
        },
      },
      animation: {
        fadeUp: 'fadeUp .35s ease-out both',
        fadeIn: 'fadeIn .25s ease-out both',
        pulseSoft: 'pulseSoft 1.6s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
