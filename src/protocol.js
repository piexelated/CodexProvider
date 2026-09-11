export class HttpError extends Error {
  constructor(status, message, code = 'invalid_request_error') {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const invalid = (message) => { throw new HttpError(400, message); };

export function parseRequest(body, models) {
  if (!isObject(body)) invalid('Expected a JSON object.');
  if (typeof body.model !== 'string' || !Object.hasOwn(models, body.model)) {
    throw new HttpError(404, 'Unknown model. Choose a model returned by /v1/models.', 'model_not_found');
  }
  if (body.stream !== undefined && typeof body.stream !== 'boolean') invalid('stream must be a boolean.');
  if (body.n !== undefined && body.n !== 1) invalid('Only n=1 is supported.');
  if (body.tools !== undefined && !Array.isArray(body.tools)) invalid('tools must be an array.');
  if (body.tools?.length && body.tool_choice !== 'none') {
    invalid('Tool calling is not supported. Disable Tool calling for this provider in JetBrains.');
  }
  if (body.tool_choice !== undefined && !['none', 'auto'].includes(body.tool_choice)) {
    invalid('This provider cannot select or call tools.');
  }
  if (body.functions?.length || body.function_call) invalid('Legacy function calling is not supported.');
  if (body.stop !== undefined && body.stop !== null && !(Array.isArray(body.stop) && body.stop.length === 0)) {
    invalid('stop sequences are not supported by the Codex SDK bridge.');
  }
  if (body.logprobs || body.top_logprobs) invalid('Token probabilities are not supported.');
  if (body.modalities && (!Array.isArray(body.modalities) || body.modalities.some((x) => x !== 'text'))) {
    invalid('Only text output is supported.');
  }
  if (!Array.isArray(body.messages) || body.messages.length === 0 || body.messages.length > 500) {
    invalid('messages must contain between 1 and 500 messages.');
  }
  const messages = body.messages.map((message) => {
    if (!isObject(message) || !['system', 'developer', 'user', 'assistant'].includes(message.role)) {
      invalid('Only system, developer, user, and assistant text messages are supported.');
    }
    if (message.tool_calls?.length || message.function_call) invalid('Tool messages are not supported.');
    let content = message.content;
    if (Array.isArray(content)) {
      content = content.map((part) => {
        if (!isObject(part) || part.type !== 'text' || typeof part.text !== 'string') {
          invalid('Only text content is supported; images, audio, and file attachments are not accepted.');
        }
        return part.text;
      }).join('\n');
    }
    if (typeof content !== 'string') invalid('Each message must have text content.');
    return { role: message.role, content };
  });
  let outputSchema;
  let jsonOutput = false;
  const format = body.response_format;
  if (format !== undefined) {
    if (!isObject(format)) invalid('response_format must be an object.');
    if (format.type === 'json_schema') {
      if (!isObject(format.json_schema?.schema)) invalid('json_schema.schema must be an object.');
      outputSchema = format.json_schema.schema;
      jsonOutput = true;
    } else if (format.type === 'json_object') {
      jsonOutput = true;
    } else if (format.type !== 'text') invalid('Unsupported response_format.');
  }
  const effort = body.reasoning_effort;
  if (effort !== undefined && !['minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'].includes(effort)) {
    invalid('Unsupported reasoning_effort.');
  }
  return {
    model: body.model,
    codexModel: models[body.model],
    messages,
    stream: body.stream === true,
    includeUsage: body.stream_options?.include_usage === true,
    outputSchema,
    jsonOutput,
    effort,
  };
}

export function buildPrompt(request) {
  // The SDK accepts a turn prompt, not Chat Completions role messages. Keep the
  // transcript ordered and escaped; never interpolate it into shell arguments.
  return [
    'Answer the final user request in the following JSON conversation transcript.',
    'Respect its system/developer instructions and use earlier messages as context.',
    'Return only the requested answer, preserving the exact requested format.',
    'Do not add progress reports, explanations, Markdown fences, or headings unless requested.',
    'You are providing text to an IDE. Do not inspect files, run tools, or modify files.',
    request.jsonOutput ? 'Return valid JSON only, without Markdown fences.' : '',
    JSON.stringify(request.messages),
  ].filter(Boolean).join('\n\n');
}

export function usageForOpenAI(usage) {
  if (!usage) return undefined;
  return {
    prompt_tokens: usage.input_tokens,
    completion_tokens: usage.output_tokens,
    total_tokens: usage.input_tokens + usage.output_tokens,
    prompt_tokens_details: { cached_tokens: usage.cached_input_tokens ?? 0 },
    completion_tokens_details: { reasoning_tokens: usage.reasoning_output_tokens ?? 0 },
  };
}

export function errorBody(error) {
  return { error: { message: error.message, type: error.status < 500 ? 'invalid_request_error' : 'server_error', param: null, code: error.code } };
}
