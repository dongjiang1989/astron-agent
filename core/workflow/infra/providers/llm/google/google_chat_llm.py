"""
Google Gemini Chat AI implementation using official Google Generative AI SDK.

This module integrates with the official Google Generative AI SDK to connect
with Gemini models.
"""

import json
from typing import Any, AsyncIterator, Dict, List, Tuple

# Import Google GenAI SDK with support for both deprecated and new package names
try:
    import google.genai as genai
    from google.genai import GenerativeModel
    from google.genai.types import AsyncGenerateContentResponse
    from google.genai.types.content_types import Part, Content
except ImportError:
    # Fallback to deprecated package during transition period
    import google.generativeai as genai
    from google.generativeai import GenerativeModel
    from google.generativeai.types import AsyncGenerateContentResponse
    from google.generativeai.types.content_types import Part, Content

from workflow.consts.engine.chat_status import ChatStatus
from workflow.engine.nodes.entities.llm_response import LLMResponse
from workflow.exception.e import CustomException
from workflow.exception.errors.err_code import CodeEnum
from workflow.extensions.otlp.log_trace.node_log import NodeLog
from workflow.extensions.otlp.trace.span import Span
from workflow.infra.providers.llm.chat_ai import ChatAI


class GoogleChatAI(ChatAI):
    """
    Google Gemini Chat AI implementation using official Google Generative AI SDK.

    This class implements the ChatAI interface to provide integration with
    Google's Gemini models using their official Python SDK.
    """

    model_config = {"arbitrary_types_allowed": True, "protected_namespaces": ()}

    def token_calculation(self, text: str) -> int:
        """Token calculation is not implemented for Google."""
        raise NotImplementedError

    def image_processing(self, image_path: str) -> Any:
        """Image processing is not implemented for Google."""
        raise NotImplementedError

    async def assemble_url(self, span: Span) -> str:
        """
        Assemble URL for Google API calls.

        Returns the configured model URL or a default placeholder if not set.
        This allows for custom Google-compatible endpoints.

        Args:
            span: OpenTelemetry span for tracing

        Returns:
            Configured API URL or placeholder
        """
        # If model_url is provided, use it as the base URL for Google client
        if self.model_url:
            await span.add_info_events_async({"google_base_url": self.model_url})
            return self.model_url
        else:
            # For standard Google API, return a placeholder
            return "google-genai-sdk-placeholder"

    def assemble_payload(self, message: list) -> Dict[str, Any]:
        """
        Assemble the payload for Google API calls.

        This method transforms the internal message format into the format
        expected by the Google GenAI SDK. This is primarily for compatibility
        with the base ChatAI interface, as the SDK handles message conversion internally.

        Args:
            message: List of message objects with role, content, and content_type

        Returns:
            Dictionary containing the formatted payload
        """
        # This method is not needed when using the SDK directly
        # The SDK handles message conversion internally
        system_parts: List[str] = []
        converted_messages: List[Dict[str, Any]] = []

        for item in message:
            role = item.get("role", "user")

            # Handle system messages separately
            if role == "system":
                system_parts.append(str(item.get("content", "")))
                continue

            content_type = item.get("content_type", "text")

            # Handle image content
            if content_type == "image":
                # For image content, we convert to the format expected by Google GenAI
                converted_messages.append({
                    "role": "user",
                    "parts": [
                        Part.from_data(mime_type="image/jpeg", data=item.get("content", "")),
                    ]
                })
            else:
                # Handle text content
                converted_messages.append({
                    "role": "model" if role == "assistant" else "user",
                    "parts": [str(item.get("content", ""))]
                })

        payload: Dict[str, Any] = {
            "messages": converted_messages,
            "system_instruction": "\n".join(system_parts) if system_parts else None,
        }

        return payload

    def decode_message(self, msg: dict) -> Tuple[str, str, str, Dict[str, Any]]:
        """
        Decode a message from the normalized response format.

        This method extracts the status, content, reasoning content, and token usage
        from the normalized response dictionary.

        Args:
            msg: Normalized response dictionary

        Returns:
            Tuple containing (status, content, reasoning_content, token_usage)
        """
        choice = msg["choices"][0]
        delta = choice.get("delta", {})
        finish_reason = choice.get("finish_reason")

        # Determine status based on finish reason
        status = ""
        if finish_reason in {ChatStatus.FINISH_REASON.value, "STOP", "stop"}:
            status = ChatStatus.FINISH_REASON.value
        elif finish_reason:
            status = str(finish_reason).lower()

        # Extract content fields
        content = delta.get("content", "")
        reasoning_content = delta.get("reasoning_content", "")
        token_usage = msg.get("usage") or {}

        return status, content, reasoning_content, token_usage

    async def _convert_messages_to_genai_format(self, message: list) -> List[Content]:
        """
        Convert the internal message format to Google GenAI format.

        This helper method transforms messages from the internal representation
        to the Content format expected by Google's Generative AI SDK.

        Args:
            message: List of message objects with role, content, and content_type

        Returns:
            List of Content objects formatted for Google GenAI
        """
        contents = []

        for item in message:
            role = item.get("role", "user")
            content_type = item.get("content_type", "text")

            # Skip system messages as they are handled separately
            if role == "system":
                # System instructions are handled separately
                continue

            if content_type == "image":
                # Handle image content
                image_data = item.get("content", "")
                if isinstance(image_data, str):
                    # Assuming it's base64 encoded image data
                    part = Part.from_data(mime_type="image/jpeg", data=image_data)
                else:
                    # Handle other formats if needed
                    continue
                contents.append(Content(role="user", parts=[part]))
            else:
                # Handle text content
                text_content = str(item.get("content", ""))
                contents.append(Content(
                    role="user" if role != "assistant" else "model",
                    parts=[text_content]
                ))

        return contents

    async def _recv_messages(
        self,
        url: str,
        user_message: list,
        extra_params: dict,
        span: Span,
        timeout: float | None = None,
    ) -> AsyncIterator[LLMResponse]:
        """
        Receive messages using Google Generative AI SDK streaming.

        This method handles the streaming response from the Google GenAI API,
        processes chunks, and yields LLMResponse objects.

        Args:
            url: API endpoint URL (used for custom Google-compatible endpoints)
            user_message: List of user messages to send
            extra_params: Additional parameters for the API call
            span: OpenTelemetry span for tracing
            timeout: Request timeout in seconds

        Yields:
            LLMResponse objects containing normalized API responses
        """
        # Configure the API key and potentially custom client options for Google GenAI
        # If a custom URL is provided, we use client_options to configure it
        if url and url != "google-genai-sdk-placeholder":
            # Use client_options to configure a custom endpoint
            genai.configure(api_key=self.api_key, client_options={"api_endpoint": url})
        else:
            # Standard Google API configuration
            genai.configure(api_key=self.api_key)

        # Create the generative model instance with potential system instruction
        model = GenerativeModel(
            model_name=self.model_name,
            system_instruction=extra_params.get("system_instruction") if "system_instruction" in extra_params else None
        )

        # Convert messages to the format expected by Google GenAI
        contents = await self._convert_messages_to_genai_format(user_message)

        # Prepare generation configuration with base parameters
        generation_config = {
            "max_output_tokens": self.max_tokens,
            "temperature": self.temperature,
        }

        # Update with any extra parameters
        if extra_params:
            for key, value in extra_params.items():
                if key not in ['system_instruction', 'messages']:  # Skip these as they're handled separately
                    generation_config[key] = value

        # Set up safety settings (default to none for now, but can be customized)
        safety_settings = None

        try:
            # Create the async stream with specified configuration
            response: AsyncGenerateContentResponse = await model.generate_content_async(
                contents,
                generation_config=generation_config,
                safety_settings=safety_settings,
                stream=True
            )

            # Process the streamed response chunks
            async for chunk in await response.async_iter():
                # Extract text from the chunk
                text = chunk.text if hasattr(chunk, 'text') else ''

                # Calculate usage info (this might not be available in streaming chunks)
                usage = {}
                if hasattr(chunk, 'usage_metadata'):
                    usage_metadata = chunk.usage_metadata
                    usage = {
                        "prompt_tokens": getattr(usage_metadata, 'prompt_token_count', 0),
                        "completion_tokens": getattr(usage_metadata, 'candidates_token_count', 0),
                        "total_tokens": getattr(usage_metadata, 'total_token_count', 0),
                    }

                # Create normalized response structure similar to OpenAI
                normalized_response = {
                    "choices": [
                        {
                            "delta": {
                                "content": text,
                                "reasoning_content": "",  # Google doesn't typically provide separate reasoning in streams
                            },
                            "finish_reason": None,  # Will be set on final chunk
                        }
                    ],
                    "usage": usage,
                }

                # Log the received message for tracing
                await span.add_info_events_async(
                    {"recv": json.dumps(normalized_response, ensure_ascii=False)}
                )

                # Yield the response
                yield LLMResponse(msg=normalized_response)

            # Send final message indicating completion
            final_response = {
                "choices": [
                    {
                        "delta": {"content": "", "reasoning_content": ""},
                        "finish_reason": ChatStatus.FINISH_REASON.value,
                    }
                ],
                "usage": usage,  # Final usage statistics
            }

            await span.add_info_events_async(
                {"recv": json.dumps(final_response, ensure_ascii=False)}
            )

            yield LLMResponse(msg=final_response)

        except Exception as e:
            raise CustomException(
                err_code=CodeEnum.OPEN_AI_REQUEST_ERROR,
                err_msg=f"Google Generative AI error: {str(e)}",
                cause_error=str(e),
            ) from e

    async def achat(
        self,
        flow_id: str,
        user_message: list,
        span: Span,
        extra_params: dict = {},
        timeout: float | None = None,
        search_disable: bool = True,
        event_log_node_trace: NodeLog | None = None,
    ) -> AsyncIterator[LLMResponse]:
        """
        Asynchronous chat method that initiates a conversation with Google Gemini.

        This method orchestrates the chat interaction, including setting up spans,
        logging events, and processing the streamed responses.

        Args:
            flow_id: Unique identifier for the workflow flow
            user_message: List of messages from the user
            span: OpenTelemetry span for tracing
            extra_params: Additional parameters for the API call
            timeout: Request timeout in seconds
            search_disable: Whether to disable search functionality
            event_log_node_trace: Node logger for event tracing

        Yields:
            LLMResponse objects containing the API responses
        """
        # Set up tracing information
        url = await self.assemble_url(span)
        await span.add_info_events_async({"domain": self.model_name})
        await span.add_info_events_async(
            {"extra_params": json.dumps(extra_params, ensure_ascii=False)}
        )

        try:
            # Add configuration data to event log if provided
            if event_log_node_trace:
                event_log_node_trace.append_config_data(
                    {
                        "model_name": self.model_name,
                        "base_url": url,  # Log the base URL used
                        "message": user_message,
                        "extra_params": extra_params,
                    }
                )

            # Process the streamed responses
            async for msg in self._recv_messages(
                url, user_message, extra_params, span, timeout
            ):
                # Add response to event log if provided
                if event_log_node_trace:
                    event_log_node_trace.add_info_log(
                        json.dumps(msg.msg, ensure_ascii=False)
                    )
                yield msg
        except CustomException as e:
            raise e
        except Exception as e:
            span.record_exception(e)
            raise CustomException(
                err_code=CodeEnum.OPEN_AI_REQUEST_ERROR,
                err_msg=str(e),
                cause_error=str(e),
            ) from e