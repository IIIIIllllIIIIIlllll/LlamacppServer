function getModelIcon(architecture) {
    const archName = (architecture || '').toLowerCase().replace(/[^a-z]/g, '');
    const iconMap = {
        'qwen': 'icon/qwen.png',
        'glm': 'icon/glm.png',
        'hunyuan': 'icon/hunyuan.png',
        'mistral': 'icon/mistral3.png',
        'gpt': 'icon/openai.png',
        'seed': 'icon/seed_oss.png',
        'llama': 'icon/llama.png',
        'kimi': 'icon/kimi.png',
        'minimax': 'icon/minimax.png',
        'gemma': 'icon/gemma.png',
		'deepseek': 'icon/deepseek.png',
		'deepseek2': 'icon/deepseek.png',
		'deepseek3': 'icon/deepseek.png',
        'step': 'icon/step35.png',
        'paddleocr': 'icon/paddleocr.png',
		'bailingmoe': 'icon/bailingmoe.png',
		'lfm': 'icon/lfm.png',
		'lfm2': 'icon/lfm.png', 
		'nemotron': 'icon/nemotron.png',
		'bert': 'icon/bert.png'
    };
    for (const [key, icon] of Object.entries(iconMap)) {
        if (archName.includes(key)) return icon;
    }
    return null;
}