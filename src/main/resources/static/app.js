(() => {
    'use strict';

    const PAUSE_MS = 2500;
    const MAX_BUFFER_CHARS = 400;

    // Uma "cena" do storyboard só vira card depois de acumular pelo menos esses
    // trechos já corrigidos (ou esse tanto de texto) — cada card deve conseguir
    // representar várias ações/ideias, não só a última pausa da fala.
    const SCENE_MIN_SEGMENTS = 2;
    const SCENE_MAX_CHARS = 600;

    const SpeechRecognitionClass = window.SpeechRecognition || window.webkitSpeechRecognition;

    const startBtn = document.getElementById('btn-start');
    const stopBtn = document.getElementById('btn-stop');
    const statusPillEl = document.getElementById('status-pill');
    const statusTextEl = document.getElementById('status-text');
    const transcriptEl = document.getElementById('transcript');
    const storyboardEl = document.getElementById('storyboard');
    const unsupportedBannerEl = document.getElementById('unsupported-banner');
    const permissionBannerEl = document.getElementById('permission-banner');
    const permissionMessageEl = document.getElementById('permission-message');

    let recognition = null;
    let state = 'stopped'; // 'stopped' | 'recording'
    let sessionStartTime = 0;
    let pendingBuffer = '';
    let interimText = '';
    let pauseTimer = null;
    let nextSegmentId = 1;

    // Cada trecho enviado para /api/correct vira um "segmento": o texto bruto
    // reconhecido pelo navegador (rawText) é exibido de imediato e, quando a IA
    // responde, é substituído por correctedText diretamente no painel de
    // transcrição.
    let transcriptSegments = [];

    // Acumula o texto já corrigido de vários segmentos até haver conteúdo
    // suficiente para gerar um card de storyboard com várias ações/ideias.
    let sceneBuffer = '';
    let sceneSegmentCount = 0;

    function checkSupport() {
        if (!SpeechRecognitionClass) {
            unsupportedBannerEl.hidden = false;
            startBtn.disabled = true;
            startBtn.title = 'Reconhecimento de fala não suportado neste navegador';
        }
    }

    function showPermissionBanner(message) {
        permissionMessageEl.textContent = message;
        permissionBannerEl.hidden = false;
    }

    function hidePermissionBanner() {
        permissionBannerEl.hidden = true;
    }

    function formatElapsed(ms) {
        const totalSeconds = Math.max(0, Math.floor(ms / 1000));
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        return String(minutes).padStart(2, '0') + ':' + String(seconds).padStart(2, '0');
    }

    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str == null ? '' : String(str);
        return div.innerHTML;
    }

    function renderTranscript() {
        transcriptEl.innerHTML = '';
        const hasContent = transcriptSegments.length > 0 || pendingBuffer || interimText;
        if (!hasContent) {
            const placeholder = document.createElement('span');
            placeholder.className = 'placeholder';
            placeholder.textContent = 'A transcrição aparecerá aqui assim que você iniciar a gravação…';
            transcriptEl.appendChild(placeholder);
            return;
        }

        for (const segment of transcriptSegments) {
            const span = document.createElement('span');
            const isCorrected = segment.correctedText != null;
            span.className = 'final-text' + (isCorrected ? '' : ' segment-pending');
            span.textContent = (isCorrected ? segment.correctedText : segment.rawText) + ' ';
            transcriptEl.appendChild(span);
        }

        if (pendingBuffer) {
            const pendingSpan = document.createElement('span');
            pendingSpan.className = 'final-text';
            pendingSpan.textContent = pendingBuffer;
            transcriptEl.appendChild(pendingSpan);
        }

        if (interimText) {
            const interimSpan = document.createElement('span');
            interimSpan.className = 'interim-text';
            interimSpan.textContent = interimText;
            transcriptEl.appendChild(interimSpan);
        }
        transcriptEl.scrollTop = transcriptEl.scrollHeight;
    }

    function clearStoryboardPlaceholder() {
        const placeholder = storyboardEl.querySelector('.placeholder');
        if (placeholder) {
            placeholder.remove();
        }
    }

    function resetStoryboard() {
        storyboardEl.innerHTML = '';
        const placeholder = document.createElement('span');
        placeholder.className = 'placeholder';
        placeholder.textContent = 'Os cards do storyboard aparecerão aqui conforme a palestra avança…';
        storyboardEl.appendChild(placeholder);
    }

    function addCard(data, timeLabel) {
        clearStoryboardPlaceholder();
        const icons = Array.isArray(data.icons) && data.icons.length > 0 ? data.icons : ['📝'];
        const iconsHtml = icons
            .map((icon) => '<span class="card-icon">' + escapeHtml(icon) + '</span>')
            .join('');

        const card = document.createElement('div');
        card.className = 'card';
        card.innerHTML =
            '<div class="card-visual">' +
                '<span class="card-time">' + escapeHtml(timeLabel) + '</span>' +
                '<div class="card-icons">' + iconsHtml + '</div>' +
            '</div>' +
            '<h3 class="card-title">' + escapeHtml(data.title || '') + '</h3>';
        storyboardEl.appendChild(card);
        storyboardEl.scrollTop = storyboardEl.scrollHeight;
    }

    function addErrorCard(message, timeLabel) {
        clearStoryboardPlaceholder();
        const card = document.createElement('div');
        card.className = 'card card-error';
        card.innerHTML =
            '<div class="card-visual card-visual-error">' +
                '<span class="card-time">' + escapeHtml(timeLabel) + '</span>' +
                '<div class="card-icons"><span class="card-icon">⚠️</span></div>' +
            '</div>' +
            '<h3 class="card-title">Resumo indisponível</h3>' +
            '<p class="card-note">' + escapeHtml(message) + '</p>';
        storyboardEl.appendChild(card);
        storyboardEl.scrollTop = storyboardEl.scrollHeight;
    }

    async function postJson(path, text) {
        const response = await fetch(path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ text })
        });
        if (!response.ok) {
            let message = null;
            try {
                const errBody = await response.json();
                message = errBody && errBody.error;
            } catch (parseErr) {
                // ignore, keep default message
            }
            const error = new Error(message || 'Falha na chamada à API');
            error.friendlyMessage = message;
            throw error;
        }
        return response.json();
    }

    /**
     * Envia o texto acumulado da cena para gerar um card de storyboard com vários
     * ícones. `force` ignora os limites mínimos (usado ao encerrar a gravação, para
     * não perder o que ainda não completou uma cena inteira).
     */
    function flushScene(force) {
        const text = sceneBuffer.trim();
        if (!text) {
            return;
        }
        if (!force && sceneSegmentCount < SCENE_MIN_SEGMENTS && text.length < SCENE_MAX_CHARS) {
            return;
        }

        sceneBuffer = '';
        sceneSegmentCount = 0;
        const elapsedMs = Date.now() - sessionStartTime;
        const timeLabel = formatElapsed(elapsedMs);

        postJson('/api/summarize', text)
            .then((data) => addCard(data, timeLabel))
            .catch((err) => {
                console.error('Falha ao gerar card do storyboard:', err);
                addErrorCard(err.friendlyMessage || '⚠️ não foi possível gerar o resumo deste trecho', timeLabel);
            });
    }

    function correctSegment(segmentId) {
        const segment = transcriptSegments.find((s) => s.id === segmentId);
        if (!segment) {
            return;
        }

        postJson('/api/correct', segment.rawText)
            .then((data) => {
                segment.correctedText = (data.correctedText && data.correctedText.trim()) || segment.rawText;
            })
            .catch((err) => {
                console.error('Falha ao corrigir trecho da transcrição:', err);
                // Sem correção disponível: mantém o texto bruto, só para de "pendurar".
                segment.correctedText = segment.rawText;
            })
            .finally(() => {
                renderTranscript();
                sceneBuffer += (sceneBuffer ? ' ' : '') + segment.correctedText;
                sceneSegmentCount += 1;
                // Ao encerrar, força o card final mesmo que a cena não tenha atingido
                // o mínimo; enquanto gravando, só gera quando há conteúdo suficiente
                // para representar várias ações.
                flushScene(state === 'stopped');
            });
    }

    function schedulePauseFlush() {
        if (pauseTimer) {
            clearTimeout(pauseTimer);
        }
        pauseTimer = setTimeout(() => {
            pauseTimer = null;
            if (pendingBuffer.trim().length > 0) {
                flushPendingBuffer();
            }
        }, PAUSE_MS);
    }

    function flushPendingBuffer() {
        if (pauseTimer) {
            clearTimeout(pauseTimer);
            pauseTimer = null;
        }
        const text = pendingBuffer.trim();
        if (!text) {
            return;
        }
        pendingBuffer = '';

        const segmentId = nextSegmentId++;
        transcriptSegments.push({ id: segmentId, rawText: text, correctedText: null });
        renderTranscript();

        correctSegment(segmentId);
    }

    function handleResult(event) {
        let interim = '';
        let hasFinal = false;

        for (let i = event.resultIndex; i < event.results.length; i++) {
            const result = event.results[i];
            const piece = result[0].transcript;
            if (result.isFinal) {
                hasFinal = true;
                pendingBuffer += piece + ' ';
            } else {
                interim += piece;
            }
        }

        interimText = interim;
        renderTranscript();

        if (hasFinal) {
            if (pendingBuffer.length > MAX_BUFFER_CHARS) {
                flushPendingBuffer();
            } else {
                schedulePauseFlush();
            }
        }
    }

    function handleEnd() {
        if (state === 'recording') {
            // O navegador pode encerrar a sessão de reconhecimento periodicamente
            // mesmo em modo contínuo; reinicia automaticamente para manter a
            // transcrição rodando durante toda a palestra.
            try {
                recognition.start();
            } catch (e) {
                // start() pode lançar se uma sessão já estiver "pendente";
                // tenta novamente em breve.
                setTimeout(() => {
                    if (state === 'recording') {
                        try {
                            recognition.start();
                        } catch (e2) {
                            console.error('Não foi possível reiniciar o reconhecimento de fala:', e2);
                        }
                    }
                }, 300);
            }
        }
    }

    function handleError(event) {
        console.error('Erro no reconhecimento de fala:', event.error);

        if (event.error === 'no-speech' || event.error === 'aborted') {
            // Erros transitórios (ex.: silêncio); o auto-restart em onend cuida disso.
            return;
        }

        if (event.error === 'audio-capture') {
            showPermissionBanner(
                'Nenhum microfone ou interface de áudio foi detectado. Verifique a conexão do dispositivo e tente novamente.');
            stopRecording();
            return;
        }

        if (event.error === 'not-allowed' || event.error === 'service-not-allowed') {
            showPermissionBanner(
                'Permissão de microfone negada. Permita o acesso ao microfone nas configurações do navegador e clique em Iniciar novamente.');
            stopRecording();
            return;
        }

        // Outros erros: registra no console e deixa o auto-restart (onend) tentar continuar.
    }

    function createRecognition() {
        const instance = new SpeechRecognitionClass();
        instance.continuous = true;
        instance.interimResults = true;
        instance.lang = 'pt-BR';
        instance.onresult = handleResult;
        instance.onend = handleEnd;
        instance.onerror = handleError;
        return instance;
    }

    function updateStatusUI() {
        if (state === 'recording') {
            statusPillEl.className = 'status-pill status-recording';
            statusTextEl.textContent = '🔴 Gravando...';
        } else {
            statusPillEl.className = 'status-pill status-stopped';
            statusTextEl.textContent = 'Parado';
        }
    }

    function startRecording() {
        if (!SpeechRecognitionClass || state === 'recording') {
            return;
        }

        hidePermissionBanner();

        // Limpa o estado para começar uma nova sessão.
        transcriptSegments = [];
        nextSegmentId = 1;
        pendingBuffer = '';
        interimText = '';
        sceneBuffer = '';
        sceneSegmentCount = 0;
        if (pauseTimer) {
            clearTimeout(pauseTimer);
            pauseTimer = null;
        }
        resetStoryboard();
        renderTranscript();

        sessionStartTime = Date.now();
        state = 'recording';
        updateStatusUI();
        startBtn.disabled = true;
        stopBtn.disabled = false;

        recognition = createRecognition();
        try {
            recognition.start();
        } catch (e) {
            console.error('Falha ao iniciar o reconhecimento de fala:', e);
        }
    }

    function stopRecording() {
        if (state !== 'recording') {
            return;
        }
        state = 'stopped';
        updateStatusUI();
        startBtn.disabled = false;
        stopBtn.disabled = true;

        if (recognition) {
            try {
                recognition.stop();
            } catch (e) {
                // ignore
            }
        }

        // Envia qualquer trecho pendente imediatamente ao encerrar. A correção desse
        // último segmento (correctSegment) força um flushScene(true) ao terminar,
        // garantindo um card final mesmo que a cena não tenha atingido o mínimo.
        if (pendingBuffer.trim().length > 0) {
            flushPendingBuffer();
        } else {
            flushScene(true);
        }
        if (pauseTimer) {
            clearTimeout(pauseTimer);
            pauseTimer = null;
        }
    }

    startBtn.addEventListener('click', startRecording);
    stopBtn.addEventListener('click', stopRecording);

    checkSupport();
    updateStatusUI();
})();
