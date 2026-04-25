#!/usr/bin/env python3
"""
Export MiniLM-L6-v2 to TFLite INT8 for on-device embedding in Aura.

Requirements:
    pip install transformers torch tensorflow tf-nightly optimum[exporters]

Output (place both files in app/src/main/assets/models/):
    minilm-l6-v2-int8.tflite   ~23 MB
    bert_vocab.txt             ~200 KB
"""
import shutil, pathlib, os

ASSETS = pathlib.Path("app/src/main/assets/models")
ASSETS.mkdir(parents=True, exist_ok=True)

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"

# ── Step 1: Export to ONNX then TFLite via Optimum ────────────────────────────
print("Downloading and converting model…")

from optimum.exporters.tflite import export_from_model
from transformers import AutoTokenizer, AutoModel

tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
model = AutoModel.from_pretrained(MODEL_ID)

# Save vocab
tokenizer.save_vocabulary(str(ASSETS))
# Rename vocab file to expected name
vocab_src = ASSETS / "vocab.txt"
vocab_dst = ASSETS / "bert_vocab.txt"
if vocab_src.exists():
    shutil.move(str(vocab_src), str(vocab_dst))
    print(f"  Vocab saved → {vocab_dst}")

# ── Step 2: Convert to TFLite INT8 with dynamic range quantisation ────────────
import tensorflow as tf
import numpy as np
import torch

class MiniLMWrapper(tf.Module):
    def __init__(self, pt_model):
        super().__init__()
        self.pt_model = pt_model

    @tf.function(input_signature=[
        tf.TensorSpec([1, 128], tf.int32, name="input_ids"),
        tf.TensorSpec([1, 128], tf.int32, name="attention_mask"),
        tf.TensorSpec([1, 128], tf.int32, name="token_type_ids"),
    ])
    def __call__(self, input_ids, attention_mask, token_type_ids):
        with torch.no_grad():
            out = self.pt_model(
                input_ids=torch.tensor(input_ids.numpy()),
                attention_mask=torch.tensor(attention_mask.numpy()),
                token_type_ids=torch.tensor(token_type_ids.numpy()),
            )
        return tf.constant(out.last_hidden_state.numpy())

wrapped = MiniLMWrapper(model)
concrete_fn = wrapped.__call__.get_concrete_function()

converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_fn])
converter.optimizations = [tf.lite.Optimize.DEFAULT]
# INT8 dynamic range quantisation — no calibration dataset needed
tflite_model = converter.convert()

out_path = ASSETS / "minilm-l6-v2-int8.tflite"
out_path.write_bytes(tflite_model)
size_mb = len(tflite_model) / 1_000_000
print(f"  TFLite model saved → {out_path} ({size_mb:.1f} MB)")
print("\nDone. Copy both files to app/src/main/assets/models/ and rebuild.")
