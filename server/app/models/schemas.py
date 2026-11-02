from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    """모든 응답 모델의 부모. 파이썬에서는 snake_case, JSON에서는 camelCase를 쓴다."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
