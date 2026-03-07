/** @type {import('tailwindcss').Config} */
export default {
    content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
    darkMode: 'class',
    theme: {
        extend: {
            colors: {
                primary: {
                    50: '#eff6ff',
                    100: '#dbeafe',
                    200: '#bfdbfe',
                    300: '#93c5fd',
                    400: '#60a5fa',
                    500: '#0084ff',
                    600: '#0073e6',
                    700: '#0062cc',
                    800: '#0051a8',
                    900: '#003d80',
                },
                surface: {
                    50: '#ffffff',
                    100: '#f8f9fa',
                    200: '#f0f2f5',
                    300: '#e4e6eb',
                    400: '#d1d5db',
                    500: '#9ca3af',
                    600: '#65676b',
                    700: '#3a3b3c',
                    800: '#242526',
                    900: '#18191a',
                    950: '#0e0f10',
                },
            },
            fontFamily: {
                sans: [
                    '-apple-system', 'BlinkMacSystemFont', '"Segoe UI"',
                    'Roboto', 'Helvetica', 'Arial', 'sans-serif',
                ],
            },
            boxShadow: {
                'bubble': '0 1px 2px rgba(0,0,0,0.08)',
                'bubble-hover': '0 2px 8px rgba(0,0,0,0.12)',
                'card': '0 2px 12px rgba(0,0,0,0.08)',
                'card-dark': '0 2px 12px rgba(0,0,0,0.4)',
                'modal': '0 12px 48px rgba(0,0,0,0.15)',
                'modal-dark': '0 12px 48px rgba(0,0,0,0.6)',
            },
        },
    },
    plugins: [],
};
