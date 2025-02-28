from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from transformers import Qwen2VLForConditionalGeneration, AutoProcessor
import torch
from qwen_vl_utils import process_vision_info
from PIL import Image
import io
import cv2
import tempfile
import os
from webS import summary as web_search
from Wiki import summary as wiki_search
from typing import List, Dict

# Initialize FastAPI app
app = FastAPI()

class ChatHistory:
    def __init__(self, max_history: int = 8):
        self.history: List[Dict] = []
        self.max_history = max_history

    def add_message(self, role: str, content: str):
        self.history.append({"role": role, "content": content})
        if len(self.history) > self.max_history * 2:
            # Remove the first message if the history is full
            self.history.pop(0)

chat_history = ChatHistory()

# Load the Qwen2-VL model and processor
model = Qwen2VLForConditionalGeneration.from_pretrained(
    "Qwen/Qwen2-VL-7B-Instruct-GPTQ-Int4",
    torch_dtype=torch.float16,
    attn_implementation="flash_attention_2",
    device_map="auto",
)

# Configure the processor with min_pixels and max_pixels
min_pixels = 256 * 28 * 28
max_pixels = 640 * 28 * 28
processor = AutoProcessor.from_pretrained(
    "Qwen/Qwen2-VL-7B-Instruct-GPTQ-Int4", min_pixels=min_pixels, max_pixels=max_pixels
)

# text-only interaction endpoint
@app.post("/text/")
async def text_interaction(text: str = Form(...)):
    try:
        # Process search/wiki queries
        response_text = ""
        if text.lower().startswith("search "):
            query = text[7:]  # Remove "search " prefix
            search_result = web_search(query)
            response_text = search_result[:1000]  # Limit response length
        elif text.lower().startswith("wiki "):
            query = text[5:]  # Remove "wiki " prefix
            wiki_result = wiki_search(query)
            response_text = wiki_result[:1000]  # Limit response length
        
        if response_text:
            # For regular queries, use the model
            messages = [
                {"role": "system", "content": "You are a Akumen AI assistant.Provide an answer based only on the provided documents. If the answer is not found in the documents, respond with 'I'm not sure .Keep responses concise 1 to 3 sentense if user dont ask long answer, limited to 8 sentences."}
            ] + chat_history.history + [{"role": "user", "content": text+f"\ndocuments\n{response_text}"}]
        else:messages = [
                {"role": "system", "content": "You are a Akumen AI assistant.You are developed a Akumen AI .Keep responses concise 1 to 3 sentense if user dont ask long answer, limited to 8 sentences."}
            ] + chat_history.history + [{"role": "user", "content": text}]

        text_input_processed = processor.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True
        )
        inputs = processor(
            text=[text_input_processed],
            padding=True,
            return_tensors="pt",
        )
        inputs = inputs.to("cuda")

        generated_ids = model.generate(**inputs, max_new_tokens=256, temperature=0.7)
        generated_ids_trimmed = [
            out_ids[len(in_ids):] for in_ids, out_ids in zip(inputs.input_ids, generated_ids)
        ]
        output_text = processor.batch_decode(
            generated_ids_trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False
        )[0]

        # Add to chat history
        chat_history.add_message("user", text)
        chat_history.add_message("assistant", output_text)

        return {output_text}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

# Image-text interaction endpoint with local file upload
@app.post("/image-text/")
async def image_text_interaction(image: UploadFile = File(...), text: str = Form(...)):
    # Read and process the uploaded image
    try:
        image_data = await image.read()
        image_pil = Image.open(io.BytesIO(image_data)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Error processing image: {str(e)}")

    # Prepare the input messages for the model
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image", "image": image_pil},
                {"type": "text", "text": "Keep responses concise 1 to 3 sentense if user dont ask long answer, limited to 8 sentences.\n"+text},
            ],
        }
    ]

    # Process vision info and prepare inputs
    text_input_processed = processor.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True
    )
    image_inputs, video_inputs = process_vision_info(messages)
    inputs = processor(
        text=[text_input_processed],
        images=image_inputs,
        videos=video_inputs,
        padding=True,
        return_tensors="pt",
    )
    inputs = inputs.to("cuda")

    # Perform inference
    generated_ids = model.generate(**inputs, max_new_tokens=128)
    generated_ids_trimmed = [
        out_ids[len(in_ids):] for in_ids, out_ids in zip(inputs.input_ids, generated_ids)
    ]
    output_text = processor.batch_decode(
        generated_ids_trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False
    )
    chat_history.add_message("user", text)
    chat_history.add_message("assistant", output_text)
    return { output_text[0]}

# Video-text interaction endpoint with local file upload
@app.post("/video-text/")
async def video_text_interaction(video: UploadFile = File(...), text: str = Form(...), fps: float = Form(1.0)):
    # Save the uploaded video to a temporary file
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".mp4") as temp_file:
            temp_file.write(await video.read())
            video_path = temp_file.name

        # Extract frames from the video
        cap = cv2.VideoCapture(video_path)
        frames = []
        frame_count = 0
        while True:
            ret, frame = cap.read()
            if not ret:
                break
            if frame_count % int(cap.get(cv2.CAP_PROP_FPS) / fps) == 0:
                frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                frames.append(Image.fromarray(frame_rgb))
            frame_count += 1
        cap.release()
        os.unlink(video_path)  # Clean up the temporary file

    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Error processing video: {str(e)}")

    # Prepare the input messages for the model
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "video", "video": frames, "max_pixels": 360 * 420, "fps": fps},
                {"type": "text", "text": "Keep responses concise 1 to 3 sentense if user dont ask long answer, limited to 8 sentences.\n"+text},
            ],
        }
    ]

    # Process vision info and prepare inputs
    text_input_processed = processor.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True
    )
    image_inputs, video_inputs = process_vision_info(messages)
    inputs = processor(
        text=[text_input_processed],
        images=image_inputs,
        videos=video_inputs,
        padding=True,
        return_tensors="pt",
    )
    inputs = inputs.to("cuda")

    # Perform inference
    generated_ids = model.generate(**inputs, max_new_tokens=128)
    generated_ids_trimmed = [
        out_ids[len(in_ids):] for in_ids, out_ids in zip(inputs.input_ids, generated_ids)
    ]
    output_text = processor.batch_decode(
        generated_ids_trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False
    )
    chat_history.add_message("user", text)
    chat_history.add_message("assistant", output_text)
    return { output_text[0]}
    
# Run the app with Uvicorn
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)