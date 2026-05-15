"""
بصير AI - الخادم الخلفي
يستخدم Gemini 3.1 Pro عبر مفتاح Emergent الموحد لتنفيذ:
  - وصف المشاهد للمكفوفين
  - قراءة وتحليل المستندات (OCR + تلخيص)
  - الإجابة عن أسئلة المستخدم على الصور (اسأل بصير)
  - الترجمة السياقية الذكية
"""
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from typing import Optional
from dotenv import load_dotenv
import os
import uuid
import logging
import base64
import re

load_dotenv()

from emergentintegrations.llm.chat import LlmChat, UserMessage, ImageContent

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

EMERGENT_LLM_KEY = os.getenv("EMERGENT_LLM_KEY", "")
GEMINI_MODEL = "gemini-3.1-pro-preview"

app = FastAPI(title="Baseer AI Backend", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============ Models ============

class SceneRequest(BaseModel):
    image_base64: str = Field(..., description="صورة base64 (JPEG/PNG/WEBP)")
    mode: Optional[str] = Field("quick", description="quick | detailed | movement")


class OcrRequest(BaseModel):
    image_base64: str
    task: Optional[str] = Field(
        "summary",
        description="summary | full | dates | amounts | names | qa | simplified"
    )


class AskRequest(BaseModel):
    image_base64: str
    question: str


class TranslateRequest(BaseModel):
    text: str
    direction: Optional[str] = Field("ar_to_en", description="ar_to_en | en_to_ar")
    tone: Optional[str] = Field(
        "natural",
        description="literal | formal | casual | dating | academic | legal | brief | polite | natural"
    )


# ============ Helpers ============

_B64_RE = re.compile(r"^[A-Za-z0-9+/=\s]+$")


def _clean_b64(s: str) -> str:
    if not s:
        raise HTTPException(status_code=400, detail="image_base64 فارغ")
    # إزالة بادئة data:image/...;base64, إن وُجدت
    if "," in s and s.strip().startswith("data:"):
        s = s.split(",", 1)[1]
    s = s.strip()
    if len(s) < 100 or not _B64_RE.match(s):
        raise HTTPException(status_code=400, detail="image_base64 غير صالح")
    return s


async def _gemini_chat(system_message: str) -> LlmChat:
    if not EMERGENT_LLM_KEY:
        raise HTTPException(status_code=500, detail="EMERGENT_LLM_KEY غير مهيأ في الخادم")
    chat = LlmChat(
        api_key=EMERGENT_LLM_KEY,
        session_id=str(uuid.uuid4()),
        system_message=system_message,
    ).with_model("gemini", GEMINI_MODEL)
    return chat


async def _ask_with_image(system: str, user_text: str, image_b64: str) -> str:
    chat = await _gemini_chat(system)
    msg = UserMessage(
        text=user_text,
        file_contents=[ImageContent(image_base64=image_b64)],
    )
    try:
        resp = await chat.send_message(msg)
        return str(resp).strip()
    except Exception as e:
        logger.exception("Gemini call failed")
        raise HTTPException(status_code=502, detail=f"تعذر الاتصال بالذكاء الاصطناعي: {e}")


async def _ask_text_only(system: str, user_text: str) -> str:
    chat = await _gemini_chat(system)
    try:
        resp = await chat.send_message(UserMessage(text=user_text))
        return str(resp).strip()
    except Exception as e:
        logger.exception("Gemini call failed")
        raise HTTPException(status_code=502, detail=f"تعذر الاتصال بالذكاء الاصطناعي: {e}")


# ============ Endpoints ============

@app.get("/api/")
async def root():
    return {"app": "Baseer AI", "status": "ok", "model": GEMINI_MODEL}


@app.get("/api/health")
async def health():
    return {"status": "healthy", "key_configured": bool(EMERGENT_LLM_KEY)}


@app.post("/api/baseer/describe-scene")
async def describe_scene(req: SceneRequest):
    """وصف ذكي للمشهد المُلتقط من كاميرا المستخدم."""
    img = _clean_b64(req.image_base64)
    mode = (req.mode or "quick").lower()

    if mode == "detailed":
        system = (
            "أنت مرافق ذكي للمكفوفين. صف المشهد بتفصيل عملي يساعد الكفيف في التصرف. "
            "ابدأ بجملة قصيرة عن المكان والأشخاص، ثم: العوائق، الأبواب، النصوص الظاهرة، "
            "الاتجاهات (يمين/يسار/أمام)، المسافات التقريبية، الأشياء الخطرة. "
            "اذكر اللون والشكل والحجم عند الأهمية. تجنب الكلام الإنشائي والمجاملات. "
            "استخدم العربية الواضحة، حد أقصى 120 كلمة، بدون رموز ولا تنسيق Markdown."
        )
        prompt = "صف هذا المشهد بتفصيل عملي للكفيف"
    elif mode == "movement":
        system = (
            "أنت مساعد مشي للمكفوفين. أعطِ تنبيهات قصيرة جدًا (كلمتان أو ثلاث) عن العوائق "
            "والأشخاص المتحركين والاتجاه الآمن فقط. لا وصف عام. "
            "أمثلة: «يمين قليلًا»، «توقف»، «عائق منخفض»، «شخص أمامك»."
        )
        prompt = "ما التنبيهات الفورية المهمة في هذا المشهد للمشي الآمن؟"
    else:  # quick
        system = (
            "أنت مرافق ذكي للمكفوفين. أعطِ وصفًا سريعًا في جملتين فقط: "
            "أين أنا (مكان)، وما الأهم في المشهد. "
            "بالعربية الواضحة، حد أقصى 30 كلمة، بدون رموز."
        )
        prompt = "صف هذا المشهد بسرعة"

    description = await _ask_with_image(system, prompt, img)
    return {"description": description, "mode": mode}


@app.post("/api/baseer/ocr-analyze")
async def ocr_analyze(req: OcrRequest):
    """قراءة وتحليل المستندات: نص كامل، تلخيص، استخراج تواريخ/مبالغ/أسماء، إلخ."""
    img = _clean_b64(req.image_base64)
    task = (req.task or "summary").lower()

    base_system = (
        "أنت مساعد ذكي للمكفوفين متخصص في قراءة وتحليل المستندات. "
        "اقرأ النص الموجود في الصورة بدقة عالية ثم نفّذ المطلوب. "
        "إذا كان المستند فاتورة أو وصفة طبية أو عقد، أبرز المعلومات الحاسمة (مبلغ، تاريخ، تحذير). "
        "أجب بالعربية الواضحة، بدون رموز ولا Markdown، نص متصل بفقرات قصيرة."
    )

    prompts = {
        "summary": "اقرأ المستند ولخّصه في 4-6 أسطر تشمل النوع والنقاط الحاسمة.",
        "full": "اكتب نص المستند كاملًا كما هو في الصورة، مع الحفاظ على الترتيب.",
        "dates": "استخرج كل التواريخ المهمة في المستند مع ما يتعلق بكل تاريخ.",
        "amounts": "استخرج كل المبالغ المالية مع وصف ما يخص كل مبلغ.",
        "names": "استخرج أسماء الأشخاص والجهات والشركات.",
        "qa": "حوّل المستند إلى 5 أسئلة وأجوبة مفيدة للحفظ.",
        "simplified": "أعد صياغة المستند بلغة سهلة جدًا يفهمها أي قارئ.",
    }
    user_prompt = prompts.get(task, prompts["summary"])

    result = await _ask_with_image(base_system, user_prompt, img)
    return {"result": result, "task": task}


@app.post("/api/baseer/ask")
async def ask_baseer(req: AskRequest):
    """اسأل بصير: صورة + سؤال صوتي/نصي عن أي شيء فيها."""
    img = _clean_b64(req.image_base64)
    question = (req.question or "").strip()
    if not question:
        raise HTTPException(status_code=400, detail="السؤال فارغ")

    system = (
        "أنت بصير، مرافق ذكي للمكفوفين. مهمتك الإجابة عن سؤال المستخدم بناءً على الصورة. "
        "أجب بشكل عملي ومفيد للكفيف: ركّز على ما يسأل عنه بالضبط، اذكر الاتجاهات والمسافات "
        "والألوان عند الأهمية. إذا لم تستطع رؤية ما يسأل عنه، قُلها بوضوح. "
        "بالعربية الواضحة، حد أقصى 80 كلمة، بدون رموز ولا تنسيق."
    )
    answer = await _ask_with_image(system, question, img)
    return {"answer": answer, "question": question}


@app.post("/api/baseer/translate")
async def translate(req: TranslateRequest):
    """ترجمة سياقية ذكية مع تحكم بالنبرة."""
    text = (req.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="النص فارغ")

    direction = (req.direction or "ar_to_en").lower()
    tone = (req.tone or "natural").lower()

    src_lang, dst_lang = ("العربية", "الإنجليزية")
    if direction == "en_to_ar":
        src_lang, dst_lang = ("الإنجليزية", "العربية")

    tone_map = {
        "literal":  "ترجمة حرفية دقيقة",
        "formal":   "ترجمة رسمية لغوية صحيحة",
        "casual":   "ترجمة محادثة عادية مريحة",
        "dating":   "ترجمة لطيفة مناسبة لمحادثات التعارف والزواج",
        "academic": "ترجمة أكاديمية بمصطلحات علمية",
        "legal":    "ترجمة قانونية دقيقة",
        "brief":    "ترجمة مختصرة جدًا",
        "polite":   "ترجمة مهذبة ولطيفة",
        "natural":  "ترجمة طبيعية يفهمها الإنسان العادي",
    }
    tone_desc = tone_map.get(tone, tone_map["natural"])

    system = (
        f"أنت مترجم سياقي ذكي. مهمتك ترجمة النص من {src_lang} إلى {dst_lang} "
        f"بأسلوب: {tone_desc}. "
        "أعطِ الترجمة فقط أولًا في سطر مستقل، ثم اكتب في سطر جديد: "
        "«شرح:» متبوعًا بوصف نبرة النص الأصلي (ودي/رسمي/متردد/مهتم/رفض...) في جملة قصيرة. "
        "بدون رموز ولا Markdown."
    )

    user_msg = f"ترجم النص التالي:\n\n{text}"
    result = await _ask_text_only(system, user_msg)

    # تقسيم الترجمة عن الشرح إن وُجد
    translation = result
    explanation = ""
    if "شرح:" in result:
        parts = result.split("شرح:", 1)
        translation = parts[0].strip()
        explanation = parts[1].strip()

    return {
        "translation": translation,
        "explanation": explanation,
        "tone": tone,
        "direction": direction,
    }
